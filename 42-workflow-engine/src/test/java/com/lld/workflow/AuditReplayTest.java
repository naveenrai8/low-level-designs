package com.lld.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Proves the audit log is complete and that replaying it rebuilds exactly the live state, without firing hooks. */
class AuditReplayTest {

    /** Each entry records who, when (from the injected clock), from, to, the transition name and the new version. */
    @Test
    void entriesRecordWhoWhenFromToVersion() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        e.transition("I-1", "start", "bob", 0);
        AuditEntry c = e.auditLog().get(0);
        AuditEntry s = e.auditLog().get(1);
        assertEquals(AuditEntry.CREATE, c.transition());
        assertEquals("ann", c.actor());
        assertEquals("I-1", c.issueId());
        assertEquals(Instant.EPOCH.plusSeconds(1), c.at());
        assertEquals("bob", s.actor());
        assertEquals(Instant.EPOCH.plusSeconds(2), s.at());
        assertEquals("OPEN", s.from());
        assertEquals("IN_PROGRESS", s.to());
        assertEquals("start", s.transition());
        assertEquals(1, s.version());
    }

    /** Failed calls (illegal, guard, conflict) leave no audit entry. */
    @Test
    void failedCallsAreNotLogged() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        int n = e.auditLog().size();
        assertThrows(WorkflowException.class, () -> e.transition("I-1", "resolve", "bob", 0));
        assertThrows(WorkflowException.class, () -> e.transition("I-1", "start", "bob", 7));
        assertEquals(n, e.auditLog().size());
    }

    /** The log handed out is a copy: changing it does not change the engine's log. */
    @Test
    void logIsAppendOnlyFromOutside() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        assertThrows(UnsupportedOperationException.class, () -> e.auditLog().clear());
    }

    /** The naive reference model: state and version per issue, with the bug-flow rules written out by hand (no engine code), so it is independent of the engine. Returns the new state or null when the move is illegal or a guard says no. */
    private static String refMove(String state, String move, String actor, Map<String, String> fields) {
        return switch (move) {
            case "start" -> state.equals("OPEN") ? "IN_PROGRESS" : null;
            case "resolve" -> state.equals("IN_PROGRESS") && "yes".equals(fields.get("reviewed")) && !actor.equals(fields.get("author")) ? "DONE" : null;
            case "reopen" -> state.equals("DONE") || state.equals("IN_PROGRESS") ? "OPEN" : null;
            default -> null;
        };
    }

    /** Differential test: the same seeded random history (creates, legal and illegal moves, wrong versions, and one rename migration IN_PROGRESS to ACTIVE) is driven through the engine and through the hand-written reference model. Live state must equal the model, and replay of the log must equal both. */
    @Test
    void engineEqualsReferenceModelAndReplay() {
        for (long seed = 1; seed <= 200; seed++) {
            Random r = new Random(seed);
            InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
            e.createProject("P", Fixtures.bugFlow());
            List<String> ids = new ArrayList<>();
            Map<String, String> state = new HashMap<>();
            Map<String, Long> version = new HashMap<>();
            Map<String, Map<String, String>> fieldsOf = new HashMap<>();
            String[] names = {"start", "resolve", "reopen", "bogus"};
            boolean migrated = false;
            for (int step = 0; step < 60; step++) {
                int pick = r.nextInt(10);
                if (pick == 0 || ids.isEmpty()) {
                    String id = "I" + ids.size();
                    Map<String, String> f = Map.of("author", "ann", "reviewed", r.nextBoolean() ? "yes" : "no");
                    e.createIssue("P", id, f, "ann");
                    ids.add(id);
                    state.put(id, "OPEN");
                    version.put(id, 0L);
                    fieldsOf.put(id, f);
                } else if (step == 30) {
                    e.migrate("P", Fixtures.renamedFlow(), Map.of("IN_PROGRESS", "ACTIVE"), "admin");
                    migrated = true;
                    for (String id : ids) {
                        state.computeIfPresent(id, (k, v) -> v.equals("IN_PROGRESS") ? "ACTIVE" : v);
                        version.merge(id, 1L, Long::sum);
                    }
                } else {
                    String id = ids.get(r.nextInt(ids.size()));
                    String move = names[r.nextInt(names.length)];
                    String actor = r.nextBoolean() ? "ann" : "bob";
                    boolean wrongVersion = r.nextInt(5) == 0;
                    long expected = wrongVersion ? version.get(id) + 1 : WorkflowEngine.ANY_VERSION;
                    String cur = state.get(id);
                    String modelState = migrated && cur.equals("ACTIVE") ? refMove("IN_PROGRESS", move, actor, fieldsOf.get(id)) : refMove(cur, move, actor, fieldsOf.get(id));
                    if (modelState != null && migrated && modelState.equals("IN_PROGRESS")) {
                        modelState = "ACTIVE";
                    }
                    try {
                        e.transition(id, move, actor, expected);
                        assertTrue(!wrongVersion && modelState != null, "engine accepted a move the model refuses, seed " + seed);
                        state.put(id, modelState);
                        version.merge(id, 1L, Long::sum);
                    } catch (WorkflowException expectedFailure) {
                        assertTrue(wrongVersion || modelState == null, "engine refused a move the model allows, seed " + seed + ": " + expectedFailure.getMessage());
                    }
                }
            }
            Map<String, IssueState> live = new HashMap<>();
            for (String id : ids) {
                IssueState s = e.get(id).toState();
                live.put(id, s);
                assertEquals(state.get(id), s.state(), "seed " + seed + " " + id);
                assertEquals(version.get(id), s.version(), "seed " + seed + " " + id);
            }
            assertEquals(live, Replay.rebuild(e.auditLog()), "seed " + seed);
        }
    }

    /** Replay is a static function that takes only the log, so it has no hook to call; this just checks that rebuilding twice leaves the live hook count and the log unchanged. */
    @Test
    void replayDoesNotRefireHooks() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        AtomicInteger fired = new AtomicInteger();
        e.addHook("count", (ev, a) -> fired.incrementAndGet());
        e.transition("I-1", "start", "bob", 0);
        Replay.rebuild(e.auditLog());
        Replay.rebuild(e.auditLog());
        assertEquals(1, fired.get());
    }

    /** Replay on a three-entry log where the last row is corrupt, one case per kind of corruption, each checked on its own so each rule is tested alone. */
    @Test
    void corruptLogIsRefused() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        e.transition("I-1", "start", "bob", 0);
        e.transition("I-1", "reopen", "bob", 1);
        List<AuditEntry> log = e.auditLog();
        AuditEntry create = log.get(0);
        AuditEntry gap = new AuditEntry(Instant.EPOCH, "x", "I-1", "P", "start", "OPEN", "IN_PROGRESS", 5, 1);
        AuditEntry badFrom = new AuditEntry(Instant.EPOCH, "x", "I-1", "P", "start", "DONE", "IN_PROGRESS", 1, 1);
        AuditEntry dupCreate = new AuditEntry(Instant.EPOCH, "x", "I-1", "P", AuditEntry.CREATE, null, "OPEN", 0, 1);
        AuditEntry lateCreate = new AuditEntry(Instant.EPOCH, "x", "I-2", "P", AuditEntry.CREATE, null, "OPEN", 3, 1);
        assertThrows(WorkflowException.ReplayFailed.class, () -> Replay.rebuild(List.of(create, log.get(2))));
        assertThrows(WorkflowException.ReplayFailed.class, () -> Replay.rebuild(List.of(log.get(1))));
        assertThrows(WorkflowException.ReplayFailed.class, () -> Replay.rebuild(List.of(create, gap)));
        assertThrows(WorkflowException.ReplayFailed.class, () -> Replay.rebuild(List.of(create, badFrom)));
        assertThrows(WorkflowException.ReplayFailed.class, () -> Replay.rebuild(List.of(create, dupCreate)));
        assertThrows(WorkflowException.ReplayFailed.class, () -> Replay.rebuild(List.of(lateCreate)));
        assertEquals(Set.of("I-1"), Replay.rebuild(log).keySet());
    }
}
