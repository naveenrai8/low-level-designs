package com.lld.jiralite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.popularity.Ranked;
import com.lld.workflow.WorkflowException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One scripted story that touches all three rounds, with exact outputs asserted at every step. */
class EndToEndTest {

    /** A team works a bug from creation to being moved, while searchers and the trending chart stay correct and nothing leaks across projects. */
    @Test
    void theWholeStory() {
        Rig r = new Rig(false);
        // Round 1: create, permissions, workflow.
        assertThrows(JiraException.PermissionDenied.class, () -> r.make("carol", "WEB", "viewer cannot create"));
        assertEquals("WEB-1", r.jira.createIssue("bob", "WEB", "Checkout page crash", "bob", Set.of("Bug")).id());
        assertEquals("WEB-2", r.make("bob", "WEB", "Add dark mode", "feature").id());
        assertEquals("OPS-1", r.make("dave", "OPS", "Checkout alert noisy", "bug").id());
        assertEquals("IN_PROGRESS", r.jira.transition("bob", "WEB-1", "start", 0).status());
        assertThrows(WorkflowException.GuardFailed.class, () -> r.jira.transition("bob", "WEB-1", "done", 1));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.transition("eve", "WEB-1", "done", 1));
        // Round 2: search lags until refresh, then is exact and permission-filtered.
        assertEquals(List.of(), r.jira.search("alice", null, null, null, "checkout"));
        assertEquals(3, r.jira.pendingIndexWrites());
        r.jira.refresh();
        assertEquals(0, r.jira.pendingIndexWrites());
        assertEquals(List.of("OPS-1", "WEB-1"), r.jira.search("alice", null, null, null, "checkout"));
        assertEquals(List.of("OPS-1"), r.jira.search("dave", null, null, null, "checkout"));
        assertEquals(List.of("WEB-1"), r.jira.search("carol", "IN_PROGRESS", null, "bug", "checkout"));
        assertEquals(List.of(), r.jira.search("eve", null, null, null, "checkout"));
        // A refused change does not touch the index.
        assertEquals(0, r.jira.pendingIndexWrites());
        assertEquals(Map.of("WEB-2", Outcome.REFUSED, "WEB-1", Outcome.DONE), r.jira.bulkTransition("alice", List.of("WEB-2", "WEB-1"), "done"));
        r.jira.refresh();
        assertEquals(List.of("WEB-1"), r.jira.search("alice", "DONE", null, null, null));
        // Round 3: trending is per project and permission-filtered.
        for (int i = 0; i < 3; i++) {
            r.jira.get("carol", "WEB-1");
        }
        r.jira.get("carol", "WEB-2");
        r.jira.get("dave", "OPS-1");
        assertEquals(List.of(new Ranked("WEB-1", 5), new Ranked("WEB-2", 1)), r.jira.trendingIssues("carol", "WEB", 5));
        assertEquals(List.of(new Ranked("bug", 5), new Ranked("feature", 1)), r.jira.trendingLabels("carol", "WEB", 5));
        assertEquals(List.of(new Ranked("OPS-1", 1)), r.jira.trendingIssues("dave", "OPS", 5));
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.trendingIssues("dave", "WEB", 5));
        // The hot issue moves projects: it vanishes from WEB everywhere and starts fresh in OPS.
        assertEquals("OPS-2", r.jira.move("alice", "WEB-1", "OPS").id());
        r.jira.refresh();
        assertEquals(List.of(new Ranked("WEB-2", 1)), r.jira.trendingIssues("carol", "WEB", 5));
        assertEquals(List.of(new Ranked("feature", 1)), r.jira.trendingLabels("carol", "WEB", 5));
        assertEquals(List.of("OPS-1", "OPS-2"), r.jira.search("dave", null, null, null, "checkout"));
        assertEquals(List.of(), r.jira.search("carol", null, null, null, "checkout"));
        // Time passes: the whole window empties.
        r.advance(Duration.ofMinutes(10));
        assertEquals(List.of(), r.jira.trendingIssues("carol", "WEB", 5));
    }
}
