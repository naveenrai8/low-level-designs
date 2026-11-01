package com.lld.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Proves guards, illegal moves and the optimistic version check. */
class TransitionTest {

    /** A legal, unguarded move changes the state and bumps the version by one. */
    @Test
    void legalMoveChangesStateAndVersion() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        IssueView v = e.transition("I-1", "start", "bob", 0);
        assertEquals("IN_PROGRESS", v.state());
        assertEquals(1, v.version());
        assertEquals(Set.of("resolve", "reopen"), e.availableTransitions("I-1"));
    }

    /** A move not defined from the current state is rejected and the issue is untouched. */
    @Test
    void illegalMoveRejectedWithNoChange() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        IssueView before = e.get("I-1");
        WorkflowException.IllegalTransition ex = assertThrows(WorkflowException.IllegalTransition.class, () -> e.transition("I-1", "resolve", "bob", 0));
        assertTrue(ex.getMessage().contains("resolve") && ex.getMessage().contains("OPEN"));
        assertEquals(before, e.get("I-1"));
        assertThrows(WorkflowException.IllegalTransition.class, () -> e.transition("I-1", "nope", "bob", 0));
    }

    /** A failing guard names itself, and leaves the issue and the audit log unchanged. */
    @Test
    void failingGuardNamedAndNothingChanges() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        e.transition("I-1", "start", "bob", 0);
        int logSize = e.auditLog().size();
        WorkflowException.GuardFailed ex = assertThrows(WorkflowException.GuardFailed.class, () -> e.transition("I-1", "resolve", "ann", 1));
        assertEquals("not-author", ex.guard());
        assertEquals("IN_PROGRESS", e.get("I-1").state());
        assertEquals(1, e.get("I-1").version());
        assertEquals(logSize, e.auditLog().size());
    }

    /** Every guard must pass: the second guard runs only after the first passed, and a pass of all lets the move through. */
    @Test
    void allGuardsMustPass() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "X", Map.of("author", "ann", "reviewed", "no"), "ann");
        e.transition("X", "start", "bob", 0);
        assertEquals("reviewed", assertThrows(WorkflowException.GuardFailed.class, () -> e.transition("X", "resolve", "bob", 1)).guard());
        e.createIssue("P", "Y", Map.of("author", "ann", "reviewed", "yes"), "ann");
        e.transition("Y", "start", "bob", 0);
        assertEquals("DONE", e.transition("Y", "resolve", "bob", 1).state());
    }

    /** A guard sees an immutable snapshot, so it cannot change the issue's fields even if it tries. */
    @Test
    void guardsSeeReadOnlyView() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        List<Throwable> seen = new ArrayList<>();
        Guard sneaky = new Guard("sneaky", ctx -> {
            try {
                ctx.issue().fields().put("hacked", "1");
            } catch (UnsupportedOperationException ex) {
                seen.add(ex);
            }
            return true;
        });
        e.createProject("P", WorkflowDefinition.builder().states("A", "B").initial("A").transition("go", Set.of("A"), "B", sneaky).build());
        e.createIssue("P", "I", Map.of("k", "v"), "u");
        e.transition("I", "go", "u", 0);
        assertEquals(1, seen.size());
        assertEquals(Map.of("k", "v"), e.get("I").fields());
    }

    /** A stale expected version is rejected and nothing changes. */
    @Test
    void staleVersionRejected() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        e.transition("I-1", "start", "bob", 0);
        assertThrows(WorkflowException.VersionConflict.class, () -> e.transition("I-1", "reopen", "bob", 0));
        assertEquals("IN_PROGRESS", e.get("I-1").state());
        assertEquals("OPEN", e.transition("I-1", "reopen", "bob", WorkflowEngine.ANY_VERSION).state());
    }

    /** Many threads race with the same expected version on a self-loop move (legal before and after), so only the version check decides: exactly one wins and the rest get VersionConflict. */
    @Test
    void racingTransitionsHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 50; round++) {
            InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
            e.createProject("P", Fixtures.selfLoop());
            e.createIssue("P", "I", Map.of(), "u");
            int threads = 8;
            CountDownLatch gate = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(pool.submit(() -> {
                    gate.await();
                    try {
                        e.transition("I", "touch", "u", 0);
                        wins.incrementAndGet();
                    } catch (WorkflowException.VersionConflict c) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : fs) {
                f.get();
            }
            pool.shutdown();
            assertEquals(1, wins.get());
            assertEquals(threads - 1, conflicts.get());
            assertEquals(1, e.get("I").version());
            assertEquals(2, e.auditLog().size());
        }
    }

    /** Forced interleaving, every run: while thread A is inside the critical section, thread B starts the same move and must be blocked on the lock; after A commits, B gets VersionConflict. Without the lock B would not block and both would win. */
    @Test
    void secondCallerIsBlockedInsideTheCriticalSection() throws Exception {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.selfLoop());
        e.createIssue("P", "I", Map.of(), "u");
        AtomicReference<Throwable> bResult = new AtomicReference<>();
        AtomicReference<Thread.State> bState = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        Thread b = new Thread(() -> {
            try {
                e.transition("I", "touch", "b", 0);
            } catch (WorkflowException ex) {
                bResult.set(ex);
            }
        });
        e.criticalSectionProbe = () -> {
            if (once.compareAndSet(false, true)) {
                b.start();
                bState.set(Fixtures.awaitBlockedOrDone(b));
            }
        };
        e.transition("I", "touch", "a", 0);
        b.join();
        assertEquals(Thread.State.BLOCKED, bState.get());
        assertTrue(bResult.get() instanceof WorkflowException.VersionConflict);
        assertEquals(1, e.get("I").version());
    }

    /** A guard that calls back into the engine is refused with Reentrant, and the outer move changes nothing. */
    @Test
    void guardCallingTheEngineIsRefused() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        Guard nosy = new Guard("nosy", ctx -> {
            e.transition(ctx.issue().id(), "go", "x", WorkflowEngine.ANY_VERSION);
            return true;
        });
        e.createProject("P", WorkflowDefinition.builder().states("A", "B").initial("A").transition("go", Set.of("A"), "B", nosy).build());
        e.createIssue("P", "I", Map.of(), "u");
        assertThrows(WorkflowException.Reentrant.class, () -> e.transition("I", "go", "u", 0));
        assertEquals("A", e.get("I").state());
        assertEquals(0, e.get("I").version());
    }

    /** The "not the author" guard fails closed: if the issue has no author field the move is refused. */
    @Test
    void missingFieldFailsClosed() {
        InMemoryWorkflowEngine e = Fixtures.engine(Runnable::run, new Fixtures.FakeTicker());
        e.createProject("P", Fixtures.bugFlow());
        e.createIssue("P", "I", Map.of("reviewed", "yes"), "u");
        e.transition("I", "start", "bob", 0);
        assertEquals("not-author", assertThrows(WorkflowException.GuardFailed.class, () -> e.transition("I", "resolve", "bob", 1)).guard());
    }

    /** Duplicate project or issue is AlreadyExists and null arguments are InvalidRequest, so every deliberate error is a WorkflowException. */
    @Test
    void errorContractIsWorkflowExceptions() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        assertThrows(WorkflowException.AlreadyExists.class, () -> e.createProject("P", Fixtures.bugFlow()));
        assertThrows(WorkflowException.AlreadyExists.class, () -> e.createIssue("P", "I-1", Map.of(), "u"));
        assertThrows(WorkflowException.InvalidRequest.class, () -> e.createIssue("P", "z", Map.of(), null));
        assertThrows(WorkflowException.InvalidRequest.class, () -> e.createIssue("P", "z", null, "u"));
        assertThrows(WorkflowException.InvalidRequest.class, () -> e.transition("I-1", "start", null, 0));
    }

    /** Asking about an unknown issue or project reports NotFound. */
    @Test
    void unknownIssueAndProjectNotFound() {
        InMemoryWorkflowEngine e = Fixtures.ready();
        assertThrows(WorkflowException.NotFound.class, () -> e.get("nope"));
        assertThrows(WorkflowException.NotFound.class, () -> e.createIssue("Q", "z", Map.of(), "u"));
    }
}
