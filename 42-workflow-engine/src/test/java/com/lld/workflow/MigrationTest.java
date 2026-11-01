package com.lld.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Proves per-project workflow versions and all-or-nothing migration of in-flight issues. */
class MigrationTest {

    /** Version 2 drops IN_PROGRESS and adds REVIEW: OPEN -> REVIEW -> DONE. */
    private static WorkflowDefinition v2() {
        return WorkflowDefinition.builder().states("OPEN", "REVIEW", "DONE").initial("OPEN")
                .transition("submit", Set.of("OPEN"), "REVIEW").transition("accept", Set.of("REVIEW"), "DONE").build();
    }

    /** A project with three issues, in OPEN, IN_PROGRESS and DONE. */
    private static InMemoryWorkflowEngine threeIssues() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        e.createIssue("P", "I-2", Map.of("author", "ann", "reviewed", "yes"), "ann");
        e.transition("I-2", "start", "bob", 0);
        e.createIssue("P", "I-3", Map.of("author", "ann", "reviewed", "yes"), "ann");
        e.transition("I-3", "start", "bob", 0);
        e.transition("I-3", "resolve", "bob", 1);
        return e;
    }

    /** A complete mapping moves every issue, bumps its version and its workflow version, and the old workflow no longer applies. */
    @Test
    void completeMappingMovesEveryIssue() {
        InMemoryWorkflowEngine e = threeIssues();
        assertEquals(3, e.migrate("P", v2(), Map.of("IN_PROGRESS", "REVIEW"), "admin"));
        assertEquals("OPEN", e.get("I-1").state());
        assertEquals("REVIEW", e.get("I-2").state());
        assertEquals("DONE", e.get("I-3").state());
        assertEquals(2, e.get("I-2").workflowVersion());
        assertEquals(2, e.get("I-2").version());
        assertEquals(Set.of("accept"), e.availableTransitions("I-2"));
        assertThrows(WorkflowException.IllegalTransition.class, () -> e.transition("I-2", "resolve", "bob", WorkflowEngine.ANY_VERSION));
    }

    /** A mapping that leaves one issue in a removed state is rejected, and no issue (not even the others) is moved. */
    @Test
    void strandedIssueRejectsWholeMigration() {
        InMemoryWorkflowEngine e = threeIssues();
        List<IssueView> before = List.of(e.get("I-1"), e.get("I-2"), e.get("I-3"));
        int logSize = e.auditLog().size();
        WorkflowException.MigrationRejected ex = assertThrows(WorkflowException.MigrationRejected.class, () -> e.migrate("P", v2(), Map.of(), "admin"));
        assertTrue(ex.getMessage().contains("I-2"));
        assertEquals(before, List.of(e.get("I-1"), e.get("I-2"), e.get("I-3")));
        assertEquals(logSize, e.auditLog().size());
        e.createIssue("P", "I-4", Map.of(), "u");
        assertEquals(1, e.get("I-4").workflowVersion());
    }

    /** Issues created after a migration follow the new version; projects are independent of each other. */
    @Test
    void newIssuesUseLatestVersionAndProjectsAreIndependent() {
        InMemoryWorkflowEngine e = threeIssues();
        e.createProject("Q", Fixtures.bugFlow());
        e.createIssue("Q", "Q-1", Map.of(), "u");
        e.migrate("P", v2(), Map.of("IN_PROGRESS", "REVIEW"), "admin");
        e.createIssue("P", "I-9", Map.of(), "u");
        assertEquals(2, e.get("I-9").workflowVersion());
        assertEquals(1, e.get("Q-1").workflowVersion());
        assertEquals("IN_PROGRESS", e.transition("Q-1", "start", "u", 0).state());
    }

    /** Forced interleaving, every run: while the migration is inside its critical section, a creator thread starts and must be blocked on the lock; it then creates on the new version. Without the lock it would finish early on version 1. */
    @Test
    void createDuringMigrationWaitsAndUsesNewVersion() throws Exception {
        InMemoryWorkflowEngine e = threeIssues();
        AtomicReference<Thread.State> state = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        Thread creator = new Thread(() -> e.createIssue("P", "late", Map.of(), "u"));
        e.criticalSectionProbe = () -> {
            if (once.compareAndSet(false, true)) {
                creator.start();
                state.set(Fixtures.awaitBlockedOrDone(creator));
            }
        };
        e.migrate("P", v2(), Map.of("IN_PROGRESS", "REVIEW"), "admin");
        creator.join();
        assertEquals(Thread.State.BLOCKED, state.get());
        assertEquals(2, e.get("late").workflowVersion());
        assertEquals("OPEN", e.get("late").state());
    }

    /** Every migrated issue gets an audit entry with the migrating actor and one shared timestamp from the clock (not a placeholder). */
    @Test
    void migrationAuditEntriesCarryActorAndOneTimestamp() {
        InMemoryWorkflowEngine e = threeIssues();
        e.migrate("P", v2(), Map.of("IN_PROGRESS", "REVIEW"), "admin");
        List<AuditEntry> mig = e.auditLog().stream().filter(x -> x.transition().equals(AuditEntry.MIGRATE)).toList();
        assertEquals(3, mig.size());
        assertEquals(1, mig.stream().map(AuditEntry::at).distinct().count());
        assertTrue(mig.get(0).at().isAfter(java.time.Instant.EPOCH.plusSeconds(5)));
        assertTrue(mig.stream().allMatch(x -> x.actor().equals("admin")));
    }
}
