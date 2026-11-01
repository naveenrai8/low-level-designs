package com.lld.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * The in-memory engine.
 *
 * <p>Concurrency model: all changes (create, transition, migrate) commit inside one short {@code synchronized} block. Inside it the engine compares the caller's expected version with the stored one (the optimistic check), runs the pure guards, writes the new snapshot and appends the audit entry. Doing the append in the same block keeps each issue's log in commit order. Hooks run after the block, on the injected executor, so a slow hook never holds the lock.
 *
 * <p>Reads: {@link #get} uses a concurrent map and never blocks. During a migration a reader can see some issues already moved and others not, because each issue is replaced on its own; each single issue is always whole. {@link #availableTransitions} takes the lock briefly.
 *
 * <p>{@code createIssue} and {@code migrate} do not fire hooks; only transitions do. A guard that calls back into the engine is refused with {@code Reentrant}, because {@code synchronized} would otherwise let the nested commit through.
 */
public final class InMemoryWorkflowEngine implements WorkflowEngine {

    private final Clock clock;
    private final Ticker ticker;
    private final Executor hookExecutor;
    private final long hookBudgetNanos;
    private final int maxHookDepth;

    private final Object lock = new Object();
    private final ThreadLocal<Boolean> committing = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Map<String, List<WorkflowDefinition>> projects = new LinkedHashMap<>();
    private final Map<String, IssueView> issues = new ConcurrentHashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final Map<String, Hook> hooks = new LinkedHashMap<>();
    private final List<HookResult> results = new CopyOnWriteArrayList<>();

    /** Test seam: runs inside the critical section, after the checks and before the write. Tests use it to force an interleaving on purpose. It does nothing in production. */
    volatile Runnable criticalSectionProbe = () -> { };

    /**
     * Creates an engine.
     *
     * @param clock wall time for audit entries
     * @param ticker elapsed time for hook budgets
     * @param hookExecutor where hooks run; use {@code Runnable::run} for same-thread, or a real pool for isolation
     * @param hookBudgetNanos a hook that takes longer than this is reported as SLOW (exactly the budget is still OK)
     * @param maxHookDepth how many hook-triggered transitions may chain before the next one is refused
     */
    public InMemoryWorkflowEngine(Clock clock, Ticker ticker, Executor hookExecutor, long hookBudgetNanos, int maxHookDepth) {
        this.clock = clock;
        this.ticker = ticker;
        this.hookExecutor = hookExecutor;
        this.hookBudgetNanos = hookBudgetNanos;
        this.maxHookDepth = maxHookDepth;
    }

    /** Registers the project with its first workflow as version 1; a second project with the same name throws AlreadyExists. */
    @Override
    public void createProject(String project, WorkflowDefinition definition) {
        need(project, "project");
        need(definition, "definition");
        synchronized (lock) {
            refuseReentry();
            if (projects.containsKey(project)) {
                throw new WorkflowException.AlreadyExists("project exists: " + project);
            }
            projects.put(project, new ArrayList<>(List.of(definition)));
        }
    }

    /** Checks every issue of the project first and throws if any would be stranded; only then publishes the new version and moves them all, inside one lock hold, with one timestamp for the whole migration. */
    @Override
    public int migrate(String project, WorkflowDefinition next, Map<String, String> stateMapping, String actor) {
        need(project, "project");
        need(next, "next");
        need(stateMapping, "stateMapping");
        need(actor, "actor");
        synchronized (lock) {
            refuseReentry();
            List<WorkflowDefinition> versions = projectOrThrow(project);
            List<IssueView> mine = new ArrayList<>();
            List<String> blockers = new ArrayList<>();
            for (IssueView i : issues.values()) {
                if (!i.project().equals(project)) {
                    continue;
                }
                mine.add(i);
                String target = stateMapping.getOrDefault(i.state(), i.state());
                if (!next.states().contains(target)) {
                    blockers.add(i.id() + " in '" + i.state() + "' would land in '" + target + "'");
                }
            }
            if (!blockers.isEmpty()) {
                throw new WorkflowException.MigrationRejected(String.join("; ", blockers));
            }
            criticalSectionProbe.run();
            versions.add(next);
            int newVersion = versions.size();
            Instant when = clock.instant();
            mine.sort((a, b) -> a.id().compareTo(b.id()));
            for (IssueView i : mine) {
                String target = stateMapping.getOrDefault(i.state(), i.state());
                IssueView moved = i.moved(target, newVersion);
                issues.put(i.id(), moved);
                audit.add(new AuditEntry(when, actor, i.id(), project, AuditEntry.MIGRATE, i.state(), target, moved.version(), newVersion));
            }
            return mine.size();
        }
    }

    /** Creates the issue in the initial state of the latest workflow version and writes the create entry to the audit log. No hooks fire. */
    @Override
    public IssueView createIssue(String project, String issueId, Map<String, String> fields, String actor) {
        need(project, "project");
        need(issueId, "issueId");
        need(fields, "fields");
        need(actor, "actor");
        synchronized (lock) {
            refuseReentry();
            List<WorkflowDefinition> versions = projectOrThrow(project);
            if (issues.containsKey(issueId)) {
                throw new WorkflowException.AlreadyExists("issue exists: " + issueId);
            }
            criticalSectionProbe.run();
            int wf = versions.size();
            IssueView created = new IssueView(issueId, project, wf, versions.get(wf - 1).initial(), 0, fields);
            issues.put(issueId, created);
            audit.add(new AuditEntry(clock.instant(), actor, issueId, project, AuditEntry.CREATE, null, created.state(), 0, wf));
            return created;
        }
    }

    /** Returns the current snapshot, or throws NotFound. Never blocks. */
    @Override
    public IssueView get(String issueId) {
        IssueView v = issues.get(issueId);
        if (v == null) {
            throw new WorkflowException.NotFound("no issue " + issueId);
        }
        return v;
    }

    /** Lists transition names defined from the current state; guards are not run. Takes the lock briefly to read the project's workflow versions. */
    @Override
    public Set<String> availableTransitions(String issueId) {
        synchronized (lock) {
            IssueView v = get(issueId);
            return new LinkedHashSet<>(definitionOf(v).transitionsFrom(v.state()));
        }
    }

    /** Runs a transition as a person (depth 0); see the private overload for the steps. */
    @Override
    public IssueView transition(String issueId, String transitionName, String actor, long expectedVersion) {
        return transition(issueId, transitionName, actor, expectedVersion, 0);
    }

    /** Registers (or replaces) a hook by name. */
    @Override
    public void addHook(String name, Hook hook) {
        need(name, "name");
        need(hook, "hook");
        synchronized (lock) {
            hooks.put(name, hook);
        }
    }

    /** Returns an unmodifiable copy of the log taken under the lock. */
    @Override
    public List<AuditEntry> auditLog() {
        synchronized (lock) {
            return List.copyOf(audit);
        }
    }

    /** Returns an unmodifiable copy of the recorded hook outcomes. */
    @Override
    public List<HookResult> hookResults() {
        return List.copyOf(results);
    }

    /** Throws InvalidRequest when a required argument is null. */
    private static void need(Object value, String what) {
        if (value == null) {
            throw new WorkflowException.InvalidRequest(what + " must not be null");
        }
    }

    /** Refuses a call made from inside a guard (the thread is already evaluating guards for a commit). Call it first thing inside the lock. */
    private void refuseReentry() {
        if (committing.get()) {
            throw new WorkflowException.Reentrant("the engine cannot be called from inside a guard");
        }
    }

    /** Looks up a project's workflow versions (index 0 is version 1). Call only while holding the lock. */
    private List<WorkflowDefinition> projectOrThrow(String project) {
        List<WorkflowDefinition> versions = projects.get(project);
        if (versions == null) {
            throw new WorkflowException.NotFound("no project " + project);
        }
        return versions;
    }

    /** Returns the workflow version this issue follows. Call only while holding the lock. */
    private WorkflowDefinition definitionOf(IssueView issue) {
        return projectOrThrow(issue.project()).get(issue.workflowVersion() - 1);
    }

    /** The real transition. {@code depth} is how many hook hops led here; above the limit it is refused before anything is read. */
    private IssueView transition(String issueId, String transitionName, String actor, long expectedVersion, int depth) {
        need(issueId, "issueId");
        need(transitionName, "transitionName");
        need(actor, "actor");
        if (depth > maxHookDepth) {
            throw new WorkflowException.HookDepthExceeded("hook chain deeper than " + maxHookDepth);
        }
        IssueView before;
        IssueView after;
        synchronized (lock) {
            refuseReentry();
            before = get(issueId);
            if (expectedVersion != ANY_VERSION && expectedVersion != before.version()) {
                throw new WorkflowException.VersionConflict("issue " + issueId + " is at version " + before.version() + ", expected " + expectedVersion);
            }
            Transition t = definitionOf(before).transition(transitionName).filter(x -> x.from().contains(before.state())).orElseThrow(
                    () -> new WorkflowException.IllegalTransition("issue " + issueId + " cannot '" + transitionName + "' from '" + before.state() + "'"));
            GuardContext ctx = new GuardContext(before, actor);
            committing.set(Boolean.TRUE);
            try {
                for (Guard g : t.guards()) {
                    if (!g.test().test(ctx)) {
                        throw new WorkflowException.GuardFailed(transitionName, g.name());
                    }
                }
            } finally {
                committing.set(Boolean.FALSE);
            }
            criticalSectionProbe.run();
            after = before.moved(t.to(), before.workflowVersion());
            issues.put(issueId, after);
            audit.add(new AuditEntry(clock.instant(), actor, issueId, before.project(), transitionName, before.state(), t.to(), after.version(), after.workflowVersion()));
        }
        fireHooks(new TransitionEvent(after, transitionName, before.state(), actor, depth));
        return after;
    }

    /** Hands each hook to the executor as its own task, after the commit and outside the lock. A rejecting or failing executor is recorded as FAILED for that hook and the others are still tried. */
    private void fireHooks(TransitionEvent event) {
        Map<String, Hook> snapshot;
        synchronized (lock) {
            snapshot = new LinkedHashMap<>(hooks);
        }
        for (Map.Entry<String, Hook> h : snapshot.entrySet()) {
            try {
                hookExecutor.execute(() -> runHook(h.getKey(), h.getValue(), event));
            } catch (Throwable t) {
                record(h.getKey(), event, HookResult.Status.FAILED, 0, "not run: " + t);
                if (t instanceof VirtualMachineError vme) {
                    throw vme;
                }
            }
        }
    }

    /** Stores one hook outcome. */
    private void record(String name, TransitionEvent event, HookResult.Status status, long nanos, String error) {
        results.add(new HookResult(name, event.issue().id(), event.transition(), status, nanos, error));
    }

    /** Runs one hook, times it, and records the outcome. It catches every Throwable (even an AssertionError) so one bad hook cannot affect anything else; only a VirtualMachineError (out of memory and the like) is rethrown after it is recorded. */
    private void runHook(String name, Hook hook, TransitionEvent event) {
        long start = ticker.read();
        HookActions actions = (id, tName, expected) -> transition(id, tName, "hook:" + name, expected, event.depth() + 1);
        try {
            hook.handle(event, actions);
            long took = ticker.read() - start;
            record(name, event, took > hookBudgetNanos ? HookResult.Status.SLOW : HookResult.Status.OK, took, null);
        } catch (Throwable e) {
            record(name, event, HookResult.Status.FAILED, ticker.read() - start, e.toString());
            if (e instanceof VirtualMachineError vme) {
                throw vme;
            }
        }
    }
}
