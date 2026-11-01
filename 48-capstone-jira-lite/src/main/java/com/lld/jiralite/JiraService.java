package com.lld.jiralite;

import com.lld.popularity.Ranked;
import com.lld.popularity.Ticker;
import com.lld.popularity.WindowSpec;
import com.lld.popularity.WindowedTopK;
import com.lld.workflow.WorkflowDefinition;
import com.lld.workflow.WorkflowEngine;
import com.lld.workflow.WorkflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Jira-lite front door (see {@link Jira} for the error contract). Every public method that touches an issue goes through the same gates in the same order: first "may you see it" (BROWSE), then "may you do this" (the action), then the workflow's own rules.
 *
 * <p>State lives in three places. The workflow engine (Topic 42) owns the status and its rules. This class owns the human fields (title, assignee, labels, comments, deleted flag). The search index and the trending trackers are copies that are rebuilt from those two.
 *
 * <p>Index promise: the index is only ever written from committed state. A change marks the issue "dirty" (a small outbox). Draining the outbox re-reads the issue from the engine and writes it to the index; the mark is removed only after the write worked. With {@code readYourWrites} on, search drains first, so a search after a write always sees it. With it off, search may lag until {@link #refresh()}.
 *
 * <p>Concurrency: one lock ({@code synchronized}) covers every change and every read of the human fields, so a check and the action that follows it cannot be split. The dirty set is a thread-safe set (not guarded by the lock) because a workflow hook may mark from another thread. Known cost: index writes and same-thread hooks run while the lock is held, and the trending read sorts all live ids under it; a production version takes a snapshot under the lock and writes outside it (the drain already removes the mark before it reads, so a mark made during the write is never lost).
 *
 * <p>The engine hook {@code jira-index} is registered by name, so another hook with the same name would replace it, and its failures show only in {@code engine.hookResults()}. That is why every method here also marks dirty itself.
 */
public final class JiraService implements Jira {

    /** The human fields of one issue, kept by this class. Only touched while holding the service lock. */
    private static final class Meta {
        final String id;
        final String project;
        final String title;
        final String reporter;
        String assignee;
        final Set<String> labels;
        final List<String> comments = new ArrayList<>();
        boolean gone;

        /** Builds the record for a fresh issue. */
        Meta(String id, String project, String title, String reporter, String assignee, Set<String> labels) {
            this.id = id;
            this.project = project;
            this.title = title;
            this.reporter = reporter;
            this.assignee = assignee;
            this.labels = labels;
        }
    }

    private final WorkflowEngine engine;
    private final PermissionScheme permissions;
    private final SearchIndex index;
    private final Ticker ticker;
    private final WindowSpec window;
    private final boolean readYourWrites;

    private final Map<String, Meta> metas = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<String, WindowedTopK> trackers = new TreeMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    /** Test seam: runs just before one issue is written to the index. A test makes it throw to simulate an indexer outage. Does nothing in production. */
    volatile Runnable indexFault = () -> { };

    /**
     * Wires the service and registers the engine hook that marks changed issues dirty (this catches changes made by workflow automation, not only by this class).
     *
     * @param engine the workflow engine (Topic 42)
     * @param permissions the deny-by-default permission scheme
     * @param index the search index
     * @param ticker elapsed time for the trending window (Topic 45)
     * @param window the trending window and its bucket length
     * @param readYourWrites true to make search drain the outbox first; false to allow lag until {@link #refresh()}
     */
    public JiraService(WorkflowEngine engine, PermissionScheme permissions, SearchIndex index, Ticker ticker, WindowSpec window, boolean readYourWrites) {
        this.engine = engine;
        this.permissions = permissions;
        this.index = index;
        this.ticker = ticker;
        this.window = window;
        this.readYourWrites = readYourWrites;
        engine.addHook("jira-index", (event, actions) -> dirty.add(event.issue().id()));
    }

    /** Registers a project with its workflow and a private trending tracker. This is a platform set-up call and is not permission checked; who may do what inside is set in the scheme. */
    @Override
    public synchronized void createProject(String project, WorkflowDefinition definition) {
        engine.createProject(project, definition);
        trackers.put(project, new WindowedTopK(ticker, window));
    }

    /**
     * Creates an issue with the next key of the project (WEB-1, WEB-2, ...). Needs BROWSE and CREATE (so "every path checks BROWSE first" has no exception); the creator becomes the reporter.
     *
     * @throws JiraException.PermissionDenied if the user may not browse or create here (also for unknown projects, so existence does not leak)
     */
    @Override
    public synchronized IssueDetails createIssue(String user, String project, String title, String assignee, Set<String> labels) {
        require(user, project, Action.BROWSE);
        require(user, project, Action.CREATE);
        return create(user, user, project, title, assignee == null ? "" : assignee, lower(labels));
    }

    /** Shows an issue and counts the view for trending. Unknown, deleted and not-browsable issues all throw the same IssueNotFound. */
    @Override
    public synchronized IssueDetails get(String user, String issueId) {
        Meta m = visible(user, issueId);
        trackers.get(m.project).record(m.id);
        return details(m);
    }

    /**
     * Runs one workflow transition. Order matters: see it (BROWSE), may do it (TRANSITION), then the engine applies its own rules (state, guards, version).
     *
     * @param expectedVersion the version the caller saw, or {@code WorkflowEngine.ANY_VERSION}
     * @throws JiraException.IssueNotFound if the user cannot see the issue
     * @throws JiraException.PermissionDenied if the user can see it but not move it
     * @throws WorkflowException if the engine refuses; nothing changes then, and the index is not touched
     */
    @Override
    public synchronized IssueDetails transition(String user, String issueId, String transitionName, long expectedVersion) {
        Meta m = visible(user, issueId);
        require(user, m.project, Action.TRANSITION);
        engine.transition(issueId, transitionName, user, expectedVersion);
        dirty.add(issueId);
        trackers.get(m.project).record(issueId);
        return details(m);
    }

    /**
     * Runs the same transition on many issues. Every issue goes through the single-issue path, so each one gets its own permission check; one denial does not stop the others.
     *
     * @return per issue: DONE, DENIED (not visible or not allowed, indistinguishable), REFUSED (the workflow said no) or ERROR (anything unexpected; the rest of the list still runs)
     */
    @Override
    public synchronized Map<String, Outcome> bulkTransition(String user, List<String> issueIds, String transitionName) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        for (String id : issueIds) {
            try {
                transition(user, id, transitionName, WorkflowEngine.ANY_VERSION);
                out.put(id, Outcome.DONE);
            } catch (JiraException e) {
                out.put(id, Outcome.DENIED);
            } catch (WorkflowException e) {
                out.put(id, Outcome.REFUSED);
            } catch (RuntimeException e) {
                out.put(id, Outcome.ERROR);
            }
        }
        return out;
    }

    /** Adds a comment. Needs BROWSE and COMMENT. The comment text becomes searchable. */
    @Override
    public synchronized void comment(String user, String issueId, String text) {
        Meta m = visible(user, issueId);
        require(user, m.project, Action.COMMENT);
        m.comments.add(text);
        dirty.add(issueId);
    }

    /** Changes the assignee (null means nobody, stored as empty text like at create). Counts as editing the issue, so it needs TRANSITION; nothing is changed before the check. */
    @Override
    public synchronized void assign(String user, String issueId, String assignee) {
        Meta m = visible(user, issueId);
        require(user, m.project, Action.TRANSITION);
        m.assignee = assignee == null ? "" : assignee;
        dirty.add(issueId);
    }

    /** Deletes an issue. Needs ADMINISTER. The issue disappears from get, search and trending for everyone at once, even in lag mode, because search drops ids whose issue is gone before it answers; the index copy is cleaned on the next drain. */
    @Override
    public synchronized void delete(String user, String issueId) {
        Meta m = visible(user, issueId);
        require(user, m.project, Action.ADMINISTER);
        m.gone = true;
        dirty.add(issueId);
    }

    /**
     * Moves an issue to another project. It needs BROWSE and ADMINISTER in the source and BROWSE and CREATE in the target. The issue gets a new key there and restarts at the target's first state; the old key is deleted (nothing of it stays in the old project's search or trending, even in lag mode, for the same reason as delete). The original reporter is kept; the mover is the actor in the engine's audit log.
     *
     * @return the new issue
     */
    @Override
    public synchronized IssueDetails move(String user, String issueId, String targetProject) {
        Meta m = visible(user, issueId);
        require(user, m.project, Action.ADMINISTER);
        require(user, targetProject, Action.BROWSE);
        require(user, targetProject, Action.CREATE);
        IssueDetails moved = create(user, m.reporter, targetProject, m.title, m.assignee, new LinkedHashSet<>(m.labels));
        Meta copy = metas.get(moved.id());
        copy.comments.addAll(m.comments);
        m.gone = true;
        dirty.add(issueId);
        return details(copy);
    }

    /**
     * Moves every issue of the project to a new workflow version. Needs BROWSE and ADMINISTER. The engine fires no hooks for a migration, so this method marks every live issue of the project dirty itself; otherwise the index would keep the old statuses.
     *
     * @return how many issues the engine moved
     */
    @Override
    public synchronized int migrate(String user, String project, WorkflowDefinition next, Map<String, String> stateMapping) {
        require(user, project, Action.BROWSE);
        require(user, project, Action.ADMINISTER);
        tracker(project);
        int n = engine.migrate(project, next, stateMapping, user);
        for (Meta m : metas.values()) {
            if (m.project.equals(project) && !m.gone) {
                dirty.add(m.id);
            }
        }
        return n;
    }

    /**
     * Searches only the projects the user may browse. The allow-list is computed here and handed to the index, which never returns anything outside it.
     *
     * @throws JiraException.IndexUnavailable if the index could not be brought up to date (search refuses rather than return stale data)
     */
    @Override
    public synchronized List<String> search(String user, String status, String assignee, String label, String text) {
        if (readYourWrites) {
            drain();
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String p : trackers.keySet()) {
            if (permissions.allows(p, user, Action.BROWSE)) {
                allowed.add(p);
            }
        }
        List<String> ids = index.search(new Query(allowed, status, assignee, label == null ? null : label.toLowerCase(Locale.ROOT), text));
        List<String> live = new ArrayList<>();
        for (String id : ids) {
            Meta m = metas.get(id);
            if (m != null && !m.gone) {
                live.add(id);
            }
        }
        return live;
    }

    /** Brings the index up to date now (like an Elasticsearch refresh). Throws IndexUnavailable if any write fails; the failed issue stays dirty and is retried next time. */
    @Override
    public synchronized void refresh() {
        drain();
    }

    /** Returns how many issues are waiting to be written to the index, across all projects. For operations and tests only: it takes no user, so a real API must not expose it to ordinary callers. */
    public int pendingIndexWrites() {
        return dirty.size();
    }

    /**
     * Returns the most viewed or transitioned issues of one project in the window, highest first.
     *
     * @throws JiraException.PermissionDenied if the user may not browse the project
     * @throws JiraException.ProjectNotFound if the user may browse it but it was never created
     */
    @Override
    public synchronized List<Ranked> trendingIssues(String user, String project, int k) {
        require(user, project, Action.BROWSE);
        tracker(project);
        return liveRanked(project, k);
    }

    /** Returns trending labels of one project: each live trending issue adds its count to each of its labels. Because it is derived from live issues, deleted and moved issues cannot keep a label trending. */
    @Override
    public synchronized List<Ranked> trendingLabels(String user, String project, int k) {
        require(user, project, Action.BROWSE);
        tracker(project);
        Map<String, Long> sums = new HashMap<>();
        for (Ranked r : liveRanked(project, Integer.MAX_VALUE)) {
            for (String label : metas.get(r.id()).labels) {
                sums.merge(label, r.count(), Long::sum);
            }
        }
        return Ranked.top(sums, k);
    }

    /** Asks the project's tracker for everything, then drops entries whose issue is deleted, moved away or no longer in this project (the tracker cannot forget one id by itself). */
    private List<Ranked> liveRanked(String project, int k) {
        List<Ranked> out = new ArrayList<>();
        for (Ranked r : tracker(project).topK(Integer.MAX_VALUE)) {
            Meta m = metas.get(r.id());
            if (m != null && !m.gone && m.project.equals(project)) {
                out.add(r);
            }
        }
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    /** Returns the project's trending tracker, or throws ProjectNotFound. Call it only after the permission check, so the error cannot be used to probe project names. */
    private WindowedTopK tracker(String project) {
        WindowedTopK t = trackers.get(project);
        if (t == null) {
            throw new JiraException.ProjectNotFound(project);
        }
        return t;
    }

    /** Makes the engine issue and the human record, and marks the issue dirty. Permission was already checked by the caller. The key counter moves only after the engine accepted the issue, so a failed create burns no number. */
    private IssueDetails create(String actor, String reporter, String project, String title, String assignee, Set<String> labels) {
        tracker(project);
        int n = counters.getOrDefault(project, 0) + 1;
        String id = project + "-" + n;
        Map<String, String> fields = new HashMap<>();
        fields.put("reporter", reporter);
        fields.put("title", title);
        engine.createIssue(project, id, fields, actor);
        counters.put(project, n);
        Meta m = new Meta(id, project, title, reporter, assignee, labels);
        metas.put(id, m);
        dirty.add(id);
        return details(m);
    }

    /** Throws PermissionDenied unless the scheme allows the action. */
    private void require(String user, String project, Action action) {
        if (!permissions.allows(project, user, action)) {
            throw new JiraException.PermissionDenied(user + " may not " + action + " in " + project);
        }
    }

    /** Finds a live issue the user may browse, or throws the one IssueNotFound that hides all the reasons. */
    private Meta visible(String user, String issueId) {
        Meta m = metas.get(issueId);
        if (m == null || m.gone || !permissions.allows(m.project, user, Action.BROWSE)) {
            throw new JiraException.IssueNotFound(issueId);
        }
        return m;
    }

    /** Joins the human fields with the engine's committed status. */
    private IssueDetails details(Meta m) {
        var v = engine.get(m.id);
        return new IssueDetails(m.id, m.project, v.state(), v.version(), m.title, m.reporter, m.assignee, List.copyOf(new TreeSet<>(m.labels)), List.copyOf(m.comments));
    }

    /**
     * Writes every dirty issue to the index, built from the engine's committed state. The mark is removed first and the state is read after, so a mark added by another thread during the write is kept (it will be drained next time) instead of being erased. If the write fails the mark is put back and a generic error is thrown; the hidden key and the real cause are only in the Java cause.
     */
    private void drain() {
        for (String id : new ArrayList<>(dirty)) {
            if (!dirty.remove(id)) {
                continue;
            }
            try {
                indexFault.run();
                Meta m = metas.get(id);
                if (m == null || m.gone) {
                    index.remove(id);
                } else {
                    index.put(new IssueDoc(id, m.project, engine.get(id).state(), m.assignee, m.labels, m.title + " " + String.join(" ", m.comments)));
                }
            } catch (RuntimeException e) {
                dirty.add(id);
                throw new JiraException.IndexUnavailable("search is temporarily unavailable", e);
            }
        }
    }

    /** Lower-cases labels so "Backend" and "backend" are one label. */
    private static Set<String> lower(Set<String> labels) {
        Set<String> out = new LinkedHashSet<>();
        for (String l : labels) {
            out.add(l.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
