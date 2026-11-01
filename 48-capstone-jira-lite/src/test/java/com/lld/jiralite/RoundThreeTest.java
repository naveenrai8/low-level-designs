package com.lld.jiralite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.popularity.Ranked;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Round 3 (30 to 45 min): trending issues and labels, one tracker per project, permission-filtered, with delete and move handled. */
class RoundThreeTest {

    /** Views `n` times as the given user. */
    private static void view(Rig r, String user, String id, int n) {
        for (int i = 0; i < n; i++) {
            r.jira.get(user, id);
        }
    }

    /** Builds WEB-1 and WEB-2 (labels bug / ui) and OPS-1 (label bug). */
    private static Rig seeded() {
        Rig r = new Rig();
        r.make("bob", "WEB", "one", "bug");
        r.make("bob", "WEB", "two", "ui");
        r.make("dave", "OPS", "three", "bug");
        return r;
    }

    /** Views and transitions both count, and ties break by key. */
    @Test
    void viewsAndTransitionsAreCounted() {
        Rig r = seeded();
        view(r, "bob", "WEB-1", 2);
        view(r, "bob", "WEB-2", 1);
        r.jira.transition("bob", "WEB-2", "start", 0);
        assertEquals(List.of(new Ranked("WEB-1", 2), new Ranked("WEB-2", 2)), r.jira.trendingIssues("bob", "WEB", 10));
        assertEquals(List.of(new Ranked("WEB-1", 2)), r.jira.trendingIssues("bob", "WEB", 1));
    }

    /** Each project has its own tracker: WEB traffic never shows in OPS trending. */
    @Test
    void projectsAreIsolated() {
        Rig r = seeded();
        view(r, "alice", "WEB-1", 5);
        view(r, "alice", "OPS-1", 1);
        assertEquals(List.of(new Ranked("OPS-1", 1)), r.jira.trendingIssues("dave", "OPS", 10));
        assertEquals(List.of(new Ranked("WEB-1", 5)), r.jira.trendingIssues("bob", "WEB", 10));
    }

    /** Trending needs BROWSE on the project, for issues and for labels. */
    @Test
    void trendingIsPermissionFiltered() {
        Rig r = seeded();
        view(r, "bob", "WEB-1", 3);
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.trendingIssues("dave", "WEB", 10));
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.trendingLabels("eve", "WEB", 10));
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.trendingIssues("alice", "NOPE", 10));
        assertEquals(List.of(new Ranked("WEB-1", 3)), r.jira.trendingIssues("carol", "WEB", 10));
    }

    /** A user who cannot browse the issue cannot add a view to it, so outsiders cannot push issues up the chart. */
    @Test
    void deniedViewsAreNotCounted() {
        Rig r = seeded();
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.get("eve", "WEB-1"));
        assertEquals(List.of(), r.jira.trendingIssues("bob", "WEB", 10));
    }

    /** Label trending adds up the counts of the live issues carrying each label. */
    @Test
    void labelsAddUp() {
        Rig r = seeded();
        r.make("bob", "WEB", "four", "bug", "ui");
        view(r, "bob", "WEB-1", 2);
        view(r, "bob", "WEB-2", 1);
        view(r, "bob", "WEB-3", 4);
        assertEquals(List.of(new Ranked("bug", 6), new Ranked("ui", 5)), r.jira.trendingLabels("bob", "WEB", 10));
    }

    /** A deleted issue leaves trending at once and takes its label weight with it. */
    @Test
    void deletedIssueLeavesTrending() {
        Rig r = seeded();
        view(r, "bob", "WEB-1", 5);
        view(r, "bob", "WEB-2", 1);
        r.jira.delete("alice", "WEB-1");
        assertEquals(List.of(new Ranked("WEB-2", 1)), r.jira.trendingIssues("bob", "WEB", 10));
        assertEquals(List.of(new Ranked("ui", 1)), r.jira.trendingLabels("bob", "WEB", 10));
    }

    /** A moved issue is gone from the old project's trending and search, appears in the new project under its new key with a fresh count, and never leaks back. */
    @Test
    void movedIssueDoesNotLinger() {
        Rig r = seeded();
        view(r, "alice", "WEB-1", 5);
        IssueDetails moved = r.jira.move("alice", "WEB-1", "OPS");
        assertEquals("OPS-2", moved.id());
        assertEquals("OPEN", moved.status());
        assertEquals(List.of(), r.jira.trendingIssues("bob", "WEB", 10));
        assertEquals(List.of(), r.jira.trendingLabels("bob", "WEB", 10));
        assertEquals(List.of(), r.jira.trendingIssues("dave", "OPS", 10));
        view(r, "dave", "OPS-2", 2);
        assertEquals(List.of(new Ranked("OPS-2", 2)), r.jira.trendingIssues("dave", "OPS", 10));
        assertEquals(List.of(), r.jira.search("bob", null, null, null, "one"));
        assertEquals(List.of("OPS-2"), r.jira.search("dave", null, null, null, "one"));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.get("bob", "WEB-1"));
    }

    /** Move needs ADMINISTER in the source and CREATE in the target; having only one of them changes nothing. */
    @Test
    void moveNeedsBothRights() {
        Rig r = seeded();
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.move("bob", "WEB-1", "OPS"));
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.move("frank", "WEB-1", "OPS"));
        assertEquals("OPEN", r.jira.get("bob", "WEB-1").status());
        assertEquals(1, r.engine.auditLog().stream().filter(a -> a.project().equals("OPS")).count());
    }

    /** Comments travel with a moved issue and are searchable in the new project. */
    @Test
    void commentsSurviveAMove() {
        Rig r = seeded();
        r.jira.comment("bob", "WEB-1", "zebra");
        IssueDetails moved = r.jira.move("alice", "WEB-1", "OPS");
        assertEquals(List.of("zebra"), moved.comments());
        assertEquals(List.of("OPS-2"), r.jira.search("dave", null, null, null, "zebra"));
    }

    /** Old views fall out of the window: after the window passes, nothing is trending. */
    @Test
    void trendingForgetsOldViews() {
        Rig r = seeded();
        view(r, "bob", "WEB-1", 3);
        r.advance(Duration.ofMinutes(9));
        assertEquals(List.of(new Ranked("WEB-1", 3)), r.jira.trendingIssues("bob", "WEB", 10));
        r.advance(Duration.ofMinutes(1));
        assertEquals(List.of(), r.jira.trendingIssues("bob", "WEB", 10));
    }

    /** A transition the guard refuses is not counted as activity. */
    @Test
    void refusedTransitionIsNotCounted() {
        Rig r = seeded();
        assertThrows(com.lld.workflow.WorkflowException.IllegalTransition.class, () -> r.jira.transition("bob", "WEB-1", "done", 0));
        assertEquals(List.of(), r.jira.trendingIssues("bob", "WEB", 10));
    }
}
