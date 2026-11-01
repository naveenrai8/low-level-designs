package com.lld.workflow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** Shared test helpers: a tiny bug workflow, a fake ticker, a fixed clock and a manual executor. */
final class Fixtures {

    /** Not instantiable. */
    private Fixtures() {
    }

    /** A ticker the test moves by hand. */
    static final class FakeTicker implements Ticker {

        private final AtomicLong now = new AtomicLong();

        /** Moves the ticker forward. */
        void advance(long nanos) {
            now.addAndGet(nanos);
        }

        /** Returns the fake reading. */
        @Override
        public long read() {
            return now.get();
        }
    }

    /** An executor that keeps tasks in a queue until the test runs them, so "after commit" and "not blocking" can be seen. */
    static final class ManualExecutor implements Executor {

        final Queue<Runnable> queue = new ArrayDeque<>();

        /** Queues the task instead of running it. */
        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }

        /** Runs every queued task, including ones queued while running. */
        void drain() {
            Runnable r;
            while ((r = queue.poll()) != null) {
                r.run();
            }
        }
    }

    /** A clock that returns an instant that moves one second on every call, so audit times are distinct and repeatable. */
    static Clock steppingClock() {
        AtomicLong n = new AtomicLong();
        return new Clock() {
            /** Returns UTC. */
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            /** Ignores the zone and returns this clock. */
            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            /** Returns the next whole second after the epoch. */
            @Override
            public Instant instant() {
                return Instant.EPOCH.plusSeconds(n.incrementAndGet());
            }
        };
    }

    /** OPEN -start-> IN_PROGRESS -resolve-> DONE (guarded) -reopen-> OPEN. The resolve guard needs field "reviewed"="yes" and the actor not to be the author. */
    static WorkflowDefinition bugFlow() {
        return WorkflowDefinition.builder().states("OPEN", "IN_PROGRESS", "DONE").initial("OPEN")
                .transition("start", Set.of("OPEN"), "IN_PROGRESS")
                .transition("resolve", Set.of("IN_PROGRESS"), "DONE", Guard.fieldEquals("reviewed", "reviewed", "yes"), Guard.actorIsNotField("not-author", "author"))
                .transition("reopen", Set.of("DONE", "IN_PROGRESS"), "OPEN")
                .build();
    }

    /** The same flow as {@link #bugFlow()} after IN_PROGRESS is renamed to ACTIVE; used as the target of a rename migration. */
    static WorkflowDefinition renamedFlow() {
        return WorkflowDefinition.builder().states("OPEN", "ACTIVE", "DONE").initial("OPEN")
                .transition("start", Set.of("OPEN"), "ACTIVE")
                .transition("resolve", Set.of("ACTIVE"), "DONE", Guard.fieldEquals("reviewed", "reviewed", "yes"), Guard.actorIsNotField("not-author", "author"))
                .transition("reopen", Set.of("DONE", "ACTIVE"), "OPEN")
                .build();
    }

    /** One state A with a self-loop "touch": legal before and after every touch, so only the version check can reject a second caller. */
    static WorkflowDefinition selfLoop() {
        return WorkflowDefinition.builder().states("A").initial("A").transition("touch", Set.of("A"), "A").build();
    }

    /** Spins (no sleep) until the thread is blocked on a lock or finished, then returns its state. */
    static Thread.State awaitBlockedOrDone(Thread t) {
        Thread.State s = t.getState();
        while (s != Thread.State.BLOCKED && s != Thread.State.TERMINATED) {
            Thread.onSpinWait();
            s = t.getState();
        }
        return s;
    }

    /** An engine with same-thread hooks, a 1000ns hook budget and depth limit 3. */
    static InMemoryWorkflowEngine engine(Executor ex, Ticker t) {
        return new InMemoryWorkflowEngine(steppingClock(), t, ex, 1000, 3);
    }

    /** An engine with a project "P" using {@link #bugFlow()} and one issue "I-1" authored by ann, already reviewed. */
    static InMemoryWorkflowEngine ready() {
        InMemoryWorkflowEngine e = engine(Runnable::run, new FakeTicker());
        e.createProject("P", bugFlow());
        e.createIssue("P", "I-1", Map.of("author", "ann", "reviewed", "yes"), "ann");
        return e;
    }
}
