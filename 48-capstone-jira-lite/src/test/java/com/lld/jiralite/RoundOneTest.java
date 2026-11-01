package com.lld.jiralite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.workflow.WorkflowEngine;
import com.lld.workflow.WorkflowException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Round 1 (0 to 15 min): issues, the workflow, and permissions checked before every change. */
class RoundOneTest {

    /** Keys count up per project and a new issue starts in the workflow's first state. */
    @Test
    void issuesGetSequentialKeysAndStartOpen() {
        Rig r = new Rig();
        assertEquals("WEB-1", r.make("bob", "WEB", "first").id());
        IssueDetails second = r.make("bob", "WEB", "second");
        assertEquals("WEB-2", second.id());
        assertEquals("OPEN", second.status());
        assertEquals("OPS-1", r.make("dave", "OPS", "other project").id());
    }

    /** Create is deny-by-default: viewers, strangers and unknown projects are all refused the same way. */
    @Test
    void createNeedsTheCreateRight() {
        Rig r = new Rig();
        assertThrows(JiraException.PermissionDenied.class, () -> r.make("carol", "WEB", "viewer cannot"));
        assertThrows(JiraException.PermissionDenied.class, () -> r.make("eve", "WEB", "stranger cannot"));
        assertThrows(JiraException.PermissionDenied.class, () -> r.make("bob", "OPS", "wrong project"));
        assertThrows(JiraException.PermissionDenied.class, () -> r.make("alice", "NOPE", "unknown project"));
    }

    /** A developer walks an issue through the workflow and the version counter goes up each time. */
    @Test
    void developerMovesIssueThroughWorkflow() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        IssueDetails started = r.jira.transition("bob", "WEB-1", "start", 0);
        assertEquals("IN_PROGRESS", started.status());
        assertEquals(1, started.version());
        IssueDetails done = r.jira.transition("alice", "WEB-1", "done", 1);
        assertEquals("DONE", done.status());
        assertEquals(2, done.version());
    }

    /** The engine's guard still applies: the reporter cannot close their own issue, and nothing changes. */
    @Test
    void guardRefusalChangesNothing() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        r.jira.transition("bob", "WEB-1", "start", 0);
        assertThrows(WorkflowException.GuardFailed.class, () -> r.jira.transition("bob", "WEB-1", "done", 1));
        assertEquals("IN_PROGRESS", r.jira.get("bob", "WEB-1").status());
        assertEquals(1, r.engine.get("WEB-1").version());
    }

    /** A stale expected version is refused by the engine (optimistic check still works through the service). */
    @Test
    void staleVersionIsRefused() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        r.jira.transition("bob", "WEB-1", "start", 0);
        assertThrows(WorkflowException.VersionConflict.class, () -> r.jira.transition("alice", "WEB-1", "done", 0));
    }

    /** The permission check comes before the workflow: a viewer is denied even for a transition that would be illegal anyway. */
    @Test
    void permissionIsCheckedBeforeTheWorkflow() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.transition("carol", "WEB-1", "done", WorkflowEngine.ANY_VERSION));
        assertEquals("OPEN", r.engine.get("WEB-1").state());
        assertEquals(0, r.engine.get("WEB-1").version());
    }

    /** A stranger gets the same error for a real issue and a made-up one, so keys cannot be probed. */
    @Test
    void strangerCannotTellRealFromMissing() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        JiraException real = assertThrows(JiraException.IssueNotFound.class, () -> r.jira.transition("eve", "WEB-1", "start", 0));
        JiraException fake = assertThrows(JiraException.IssueNotFound.class, () -> r.jira.transition("eve", "WEB-99", "start", 0));
        assertEquals("no such issue: WEB-1", real.getMessage());
        assertEquals("no such issue: WEB-99", fake.getMessage());
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.get("dave", "WEB-1"));
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.comment("eve", "WEB-1", "hi"));
    }

    /** Comments need the COMMENT right: a viewer can read but not comment, a developer can. */
    @Test
    void commentNeedsTheCommentRight() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.comment("carol", "WEB-1", "nope"));
        r.jira.comment("bob", "WEB-1", "looks good");
        assertEquals(List.of("looks good"), r.jira.get("carol", "WEB-1").comments());
    }

    /** Delete needs ADMINISTER and afterwards the issue is gone for everybody. */
    @Test
    void deleteNeedsAdminister() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.delete("bob", "WEB-1"));
        r.jira.delete("alice", "WEB-1");
        assertThrows(JiraException.IssueNotFound.class, () -> r.jira.get("alice", "WEB-1"));
    }

    /** Changing the assignee is an edit, so a viewer is refused. */
    @Test
    void assignNeedsTransitionRight() {
        Rig r = new Rig();
        r.make("bob", "WEB", "t");
        assertThrows(JiraException.PermissionDenied.class, () -> r.jira.assign("carol", "WEB-1", "carol"));
        r.jira.assign("bob", "WEB-1", "bob");
        assertEquals("bob", r.jira.get("bob", "WEB-1").assignee());
    }

    /** Bulk checks each issue on its own: one done, one refused by the workflow, two denied (no rights, and unknown) and the denied ones changed nothing. */
    @Test
    void bulkChecksEveryIssue() {
        Rig r = new Rig();
        r.make("bob", "WEB", "a");
        r.make("bob", "WEB", "b");
        r.make("dave", "OPS", "c");
        r.jira.transition("bob", "WEB-2", "start", 0);
        Map<String, Outcome> out = r.jira.bulkTransition("bob", List.of("WEB-1", "WEB-2", "OPS-1", "WEB-77"), "start");
        assertEquals(Outcome.DONE, out.get("WEB-1"));
        assertEquals(Outcome.REFUSED, out.get("WEB-2"));
        assertEquals(Outcome.DENIED, out.get("OPS-1"));
        assertEquals(Outcome.DENIED, out.get("WEB-77"));
        assertEquals("OPEN", r.engine.get("OPS-1").state());
        assertEquals(List.of("WEB-1", "WEB-2", "OPS-1", "WEB-77"), List.copyOf(out.keySet()));
    }

    /** The scheme says no until something says yes, and a grant in one project says nothing about another. */
    @Test
    void schemeDeniesByDefaultAndPerProject() {
        RoleBasedPermissionScheme s = new RoleBasedPermissionScheme();
        assertFalse(s.allows("WEB", "x", Action.BROWSE));
        s.assignRole("WEB", "x", Role.VIEWER);
        assertFalse(s.allows("WEB", "x", Action.BROWSE));
        s.grant("WEB", Role.VIEWER, Action.BROWSE);
        assertTrue(s.allows("WEB", "x", Action.BROWSE));
        assertFalse(s.allows("OPS", "x", Action.BROWSE));
        assertFalse(s.allows("WEB", "x", Action.CREATE));
    }
}
