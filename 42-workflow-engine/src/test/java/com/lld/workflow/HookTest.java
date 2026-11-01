package com.lld.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves hooks run after commit, are isolated from each other, and cannot loop forever. */
class HookTest {

    /** Hooks do not run until after the transition has committed and returned; the hook sees the new state. */
    @Test
    void hooksRunAfterCommit() {
        Fixtures.ManualExecutor ex = new Fixtures.ManualExecutor();
        InMemoryWorkflowEngine e = Fixtures.engine(ex, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        List<String> seen = new ArrayList<>();
        e.addHook("h", (ev, a) -> seen.add(ev.issue().state() + "/" + ev.from()));
        e.transition("I", "start", "u", 0);
        assertEquals("IN_PROGRESS", e.get("I").state());
        assertTrue(seen.isEmpty());
        ex.drain();
        assertEquals(List.of("IN_PROGRESS/OPEN"), seen);
    }

    /** A throwing hook does not stop the transition or the next hook, and its failure is recorded. */
    @Test
    void throwingHookDoesNotBlockOthers() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        List<String> ran = new ArrayList<>();
        e.addHook("bad", (ev, a) -> {
            throw new IllegalStateException("boom");
        });
        e.addHook("good", (ev, a) -> ran.add("good"));
        assertEquals("IN_PROGRESS", e.transition("I", "start", "u", 0).state());
        assertEquals(List.of("good"), ran);
        List<HookResult> rs = e.hookResults();
        assertEquals(HookResult.Status.FAILED, rs.get(0).status());
        assertTrue(rs.get(0).error().contains("boom"));
        assertEquals(HookResult.Status.OK, rs.get(1).status());
    }

    /** A hook that takes longer than its budget (by the injected ticker) is reported SLOW, with no real waiting. */
    @Test
    void slowHookIsReported() {
        Fixtures.FakeTicker t = new Fixtures.FakeTicker();
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, t);
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        e.addHook("slow", (ev, a) -> t.advance(5000));
        e.addHook("fast", (ev, a) -> t.advance(10));
        e.transition("I", "start", "u", 0);
        assertEquals(HookResult.Status.SLOW, e.hookResults().get(0).status());
        assertEquals(5000, e.hookResults().get(0).nanos());
        assertEquals(HookResult.Status.OK, e.hookResults().get(1).status());
    }

    /** A hook that always flips the issue back and forth stops at the depth limit instead of looping forever. */
    @Test
    void hookLoopStopsAtDepthLimit() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        WorkflowDefinition ping = WorkflowDefinition.builder().states("A", "B").initial("A")
                .transition("toB", Set.of("A"), "B").transition("toA", Set.of("B"), "A").build();
        e.createProject("P", ping);
        e.createIssue("P", "I", Map.of(), "u");
        e.addHook("flip", (ev, a) -> a.transition(ev.issue().id(), ev.issue().state().equals("B") ? "toA" : "toB", ev.issue().version()));
        e.transition("I", "toB", "u", 0);
        // The user's move plus 3 hook hops (limit 3) = 4 commits, then the 4th hop is refused.
        assertEquals(4, e.get("I").version());
        long failed = e.hookResults().stream().filter(r -> r.status() == HookResult.Status.FAILED && r.error().contains("HookDepthExceeded")).count();
        assertEquals(1, failed);
    }

    /** A hook can trigger one useful follow-up transition, and that follow-up is attributed to the hook in the audit log. */
    @Test
    void hookCanTriggerOneFollowUp() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        e.addHook("auto-reopen", (ev, a) -> {
            if (ev.transition().equals("start")) {
                a.transition(ev.issue().id(), "reopen", ev.issue().version());
            }
        });
        e.transition("I", "start", "u", 0);
        assertEquals("OPEN", e.get("I").state());
        assertEquals("hook:auto-reopen", e.auditLog().get(2).actor());
    }

    /** A hook queued before a person's change runs late with its stale version: the follow-up gets VersionConflict (recorded FAILED) and the newer human change is not overwritten. */
    @Test
    void lateHookCannotOverwriteNewerChange() {
        Fixtures.ManualExecutor ex = new Fixtures.ManualExecutor();
        InMemoryWorkflowEngine e = Fixtures.engine(ex, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of("author", "ann", "reviewed", "yes"), "u");
        e.addHook("auto-reopen", (ev, a) -> {
            if (ev.transition().equals("start")) {
                a.transition(ev.issue().id(), "reopen", ev.issue().version());
            }
        });
        e.transition("I", "start", "u", 0);
        e.transition("I", "resolve", "bob", 1);
        ex.drain();
        assertEquals("DONE", e.get("I").state());
        HookResult r = e.hookResults().stream().filter(x -> x.transition().equals("start")).findFirst().orElseThrow();
        assertEquals(HookResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("VersionConflict"));
    }

    /** An Error thrown by a hook (not only an Exception) is recorded as FAILED, the transition still returns, and the next hook still runs. */
    @Test
    void errorFromHookIsContained() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        List<String> ran = new ArrayList<>();
        e.addHook("bad", (ev, a) -> {
            throw new AssertionError("nope");
        });
        e.addHook("good", (ev, a) -> ran.add("good"));
        assertEquals("IN_PROGRESS", e.transition("I", "start", "u", 0).state());
        assertEquals(List.of("good"), ran);
        assertEquals(HookResult.Status.FAILED, e.hookResults().get(0).status());
        assertTrue(e.hookResults().get(0).error().contains("AssertionError") && !e.hookResults().get(0).error().contains("not run"));
    }

    /** An executor that rejects work does not make the transition fail or skip the other hooks; each rejected hook is recorded FAILED. */
    @Test
    void rejectingExecutorIsContained() {
        List<Runnable> accepted = new ArrayList<>();
        java.util.concurrent.Executor picky = r -> {
            if (accepted.isEmpty()) {
                throw new java.util.concurrent.RejectedExecutionException("full");
            }
            accepted.add(r);
        };
        InMemoryWorkflowEngine e = Fixtures.engine(picky, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        e.addHook("h1", (ev, a) -> { });
        e.addHook("h2", (ev, a) -> { });
        assertEquals("IN_PROGRESS", e.transition("I", "start", "u", 0).state());
        assertEquals(2, e.hookResults().size());
        assertTrue(e.hookResults().stream().allMatch(r -> r.status() == HookResult.Status.FAILED && r.error().contains("not run")));
    }

    /** The budget boundary: taking exactly the budget is OK, one nanosecond more is SLOW. */
    @Test
    void budgetBoundaryIsInclusive() {
        Fixtures.FakeTicker t = new Fixtures.FakeTicker();
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, t);
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        e.addHook("exact", (ev, a) -> t.advance(1000));
        e.addHook("over", (ev, a) -> t.advance(1001));
        e.transition("I", "start", "u", 0);
        assertEquals(HookResult.Status.OK, e.hookResults().get(0).status());
        assertEquals(HookResult.Status.SLOW, e.hookResults().get(1).status());
    }

    /** Each recorded result names the hook, the issue and the transition that triggered it. */
    @Test
    void resultsNameHookIssueAndTransition() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of(), "u");
        e.addHook("h", (ev, a) -> { });
        e.transition("I", "start", "u", 0);
        HookResult r = e.hookResults().get(0);
        assertEquals("h", r.hook());
        assertEquals("I", r.issueId());
        assertEquals("start", r.transition());
    }

    /** Hook depth in numbers: the person's move is depth 0 and each hook hop adds 1, so with limit 3 the hooks see depths 0, 1, 2, 3 and the hop at depth 4 is refused. */
    @Test
    void depthCountsUpByOnePerHop() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        WorkflowDefinition ping = WorkflowDefinition.builder().states("A", "B").initial("A")
                .transition("toB", Set.of("A"), "B").transition("toA", Set.of("B"), "A").build();
        e.createProject("P", ping);
        e.createIssue("P", "I", Map.of(), "u");
        List<Integer> depths = new ArrayList<>();
        e.addHook("flip", (ev, a) -> {
            depths.add(ev.depth());
            a.transition(ev.issue().id(), ev.issue().state().equals("B") ? "toA" : "toB", ev.issue().version());
        });
        e.transition("I", "toB", "u", 0);
        assertEquals(List.of(0, 1, 2, 3), depths);
    }
}
