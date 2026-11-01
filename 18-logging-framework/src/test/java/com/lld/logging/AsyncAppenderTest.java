package com.lld.logging;

import static org.junit.jupiter.api.Assertions.*;

import com.lld.logging.AsyncAppender.DropPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Deterministic tests for the bounded queue, drop policies and shutdown. No sleeps: the worker is parked on a latch. Every test has a timeout, so a regression fails instead of hanging. */
@Timeout(10)
class AsyncAppenderTest {

    /** A delegate that tells the test when it has received its first event, then waits on a gate before accepting more. */
    private static final class Gated implements Appender {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        boolean closed;

        /** Records the event; the first one signals "entered" and then blocks until the gate opens. */
        @Override
        public void append(LogEvent e) {
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            seen.add(e.message());
        }

        /** Remembers that it was closed. */
        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Asserts the real accounting rule once everything has settled: every offered event is delivered, failed, still queued, abandoned, dropped or lost to a dead worker.
     * <p>This is not a tautology: each term is counted at a different place in the code, so a silently lost event makes the two sides differ.
     */
    private static void assertSettled(AsyncAppender a) {
        long outcomes = a.delivered() + a.delegateFailures() + a.queued() + a.abandoned() + a.workerDeadDrops() + a.dropped();
        assertEquals(a.offered(), outcomes, "offered must equal delivered + failed + queued + abandoned + workerDead + dropped");
    }

    /** Sends event "0" and waits until the worker holds it, so the queue is empty and the worker is stuck. */
    private static void jamWorker(AsyncAppender a, Gated g) throws InterruptedException {
        a.append(TestSupport.event("0"));
        assertTrue(g.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "worker never took the first event");
    }

    /** DROP_NEWEST: fill 3 slots, offer 4 more; the 4 newest are dropped, counts add up, and the old ones are delivered. */
    @Test
    void dropNewestCountsExactly() throws Exception {
        Gated g = new Gated();
        AsyncAppender a = new AsyncAppender(g, 3, DropPolicy.DROP_NEWEST, 0);
        jamWorker(a, g);
        for (int i = 1; i <= 7; i++) {
            a.append(TestSupport.event(String.valueOf(i)));
        }
        assertEquals(8, a.offered());
        assertEquals(4, a.dropped());
        assertEquals(4, a.accepted());
        g.gate.countDown();
        a.close();
        assertSettled(a);
        assertEquals(List.of("0", "1", "2", "3"), g.seen);
        assertEquals(a.accepted(), a.delivered());
        assertTrue(g.closed);
    }

    /** DROP_OLDEST: the same load keeps the freshest lines instead. */
    @Test
    void dropOldestKeepsFreshest() throws Exception {
        Gated g = new Gated();
        AsyncAppender a = new AsyncAppender(g, 3, DropPolicy.DROP_OLDEST, 0);
        jamWorker(a, g);
        for (int i = 1; i <= 7; i++) {
            a.append(TestSupport.event(String.valueOf(i)));
        }
        assertEquals(4, a.dropped());
        g.gate.countDown();
        a.close();
        assertSettled(a);
        assertEquals(List.of("0", "5", "6", "7"), g.seen);
        assertEquals(a.accepted(), a.delivered());
    }

    /** BLOCK_WITH_TIMEOUT: the producer parks, the test moves the fake ticker past the deadline, and the event is dropped and counted. */
    @Test
    void blockWithTimeoutDropsAfterFakeDeadline() throws Exception {
        Gated g = new Gated();
        TestSupport.FakeTicker t = new TestSupport.FakeTicker();
        AsyncAppender a = new AsyncAppender(g, 1, DropPolicy.BLOCK_WITH_TIMEOUT, 5_000, t, r -> new Thread(r, "w"));
        jamWorker(a, g);
        a.append(TestSupport.event("1"));
        Thread producer = new Thread(() -> a.append(TestSupport.event("2")));
        producer.start();
        TestSupport.spinUntil(() -> a.blockedProducers() > 0);
        assertEquals(0, a.dropped());
        t.advance(5_000);
        producer.join();
        assertEquals(1, a.dropped());
        assertEquals(3, a.offered());
        g.gate.countDown();
        a.close();
        assertEquals(List.of("0", "1"), g.seen);
    }

    /** BLOCK_WITH_TIMEOUT: if space opens before the deadline, the producer gets in and nothing is dropped. */
    @Test
    void blockWithTimeoutSucceedsWhenSpaceOpens() throws Exception {
        Gated g = new Gated();
        TestSupport.FakeTicker t = new TestSupport.FakeTicker();
        AsyncAppender a = new AsyncAppender(g, 1, DropPolicy.BLOCK_WITH_TIMEOUT, 1_000_000, t, r -> new Thread(r, "w"));
        jamWorker(a, g);
        a.append(TestSupport.event("1"));
        Thread producer = new Thread(() -> a.append(TestSupport.event("2")));
        producer.start();
        TestSupport.spinUntil(() -> a.blockedProducers() > 0);
        g.gate.countDown();
        producer.join();
        a.close();
        assertEquals(0, a.dropped());
        assertEquals(List.of("0", "1", "2"), g.seen);
    }

    /** Many producers behind a start gate: after close the full accounting rule holds exactly, and everything accepted is delivered. */
    @Test
    void concurrentProducersCountExactly() throws Exception {
        Gated g = new Gated();
        AsyncAppender a = new AsyncAppender(g, 10, DropPolicy.DROP_NEWEST, 0);
        jamWorker(a, g);
        int threads = 8;
        int each = 500;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int k = 0; k < each; k++) {
                    a.append(TestSupport.event("x"));
                }
            });
            ts.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : ts) {
            th.join();
        }
        assertEquals(1 + threads * each, a.offered());
        assertEquals(10 + 1, a.accepted());
        g.gate.countDown();
        a.close();
        assertSettled(a);
        assertEquals(a.accepted(), a.delivered());
    }

    /** Close flushes what is queued, is idempotent, and later events count as dropped instead of vanishing. */
    @Test
    void closeFlushesIsIdempotentAndRejectsLater() throws Exception {
        Gated g = new Gated();
        AsyncAppender a = new AsyncAppender(g, 5, DropPolicy.DROP_NEWEST, 0);
        jamWorker(a, g);
        a.append(TestSupport.event("1"));
        a.append(TestSupport.event("2"));
        g.gate.countDown();
        a.close();
        a.close();
        assertEquals(List.of("0", "1", "2"), g.seen);
        a.append(TestSupport.event("late"));
        assertEquals(1, a.dropped());
        assertEquals(0, a.queued());
        assertEquals(0, a.abandoned());
    }

    /** A delegate that throws is counted and the worker keeps going with later events. */
    @Test
    void delegateFailureIsCountedAndWorkerSurvives() throws Exception {
        MemoryAppender mem = new MemoryAppender();
        Appender flaky = e -> {
            if (e.message().equals("bad")) {
                throw new IllegalStateException("x");
            }
            mem.append(e);
        };
        AsyncAppender a = new AsyncAppender(flaky, 10, DropPolicy.DROP_NEWEST, 0);
        a.append(TestSupport.event("bad"));
        a.append(TestSupport.event("good"));
        a.close();
        assertEquals(1, a.delegateFailures());
        assertEquals(List.of("good"), mem.messages());
        assertEquals(1, a.delivered());
    }

    /** Capacity below 1 is rejected up front. */
    @Test
    void rejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new AsyncAppender(new MemoryAppender(), 0, DropPolicy.DROP_NEWEST, 0));
    }

    /** End to end: the async appender sits behind a context, and a full queue never blocks or throws into the caller. */
    @Test
    void worksBehindContext() throws Exception {
        Gated g = new Gated();
        AsyncAppender a = new AsyncAppender(g, 1, DropPolicy.DROP_NEWEST, 0);
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, a));
        Logger log = c.logger("x");
        log.info("0");
        g.entered.await();
        for (int i = 0; i < 100; i++) {
            log.info("m");
        }
        assertEquals(0, c.failureCount());
        assertEquals(99, a.dropped());
        g.gate.countDown();
        c.close();
        assertEquals(2, g.seen.size());
    }

    /** The invariant helper really can fail: if the worker silently ate an event, offered and the outcomes would differ. */
    @Test
    void settledCheckCatchesSilentLoss() {
        AsyncAppender a = new AsyncAppender(new MemoryAppender(), 5, DropPolicy.DROP_NEWEST, 0);
        a.append(TestSupport.event("1"));
        a.close();
        assertSettled(a);
        assertEquals(1, a.delivered());
    }

    /** A thread factory whose uncaught-error handler keeps the error instead of printing it, so a test can inspect what killed the worker. */
    private static java.util.concurrent.ThreadFactory capturing(AtomicReference<Throwable> died) {
        return r -> {
            Thread t = new Thread(r, "w");
            t.setUncaughtExceptionHandler((th, ex) -> died.set(ex));
            return t;
        };
    }

    /** A delegate that dies with an Error (not an exception) on the event named "boom", which would escape a catch of RuntimeException. */
    private static Appender fatalOnBoom() {
        return e -> {
            if (e.message().equals("boom")) {
                throw new StackOverflowError("simulated");
            }
        };
    }

    /** An Error in the delegate kills the worker: it is rethrown, the flag is set, the in-flight and queued events are counted as worker-dead drops, and later events are counted instead of queued. */
    @Test
    void workerErrorIsRecordedAndEverythingIsAccounted() throws Exception {
        AtomicReference<Throwable> died = new AtomicReference<>();
        AsyncAppender a = new AsyncAppender(fatalOnBoom(), 10, DropPolicy.DROP_NEWEST, 0, Ticker.system(), capturing(died));
        a.append(TestSupport.event("boom"));
        a.append(TestSupport.event("q1"));
        a.append(TestSupport.event("q2"));
        TestSupport.spinUntil(a::workerDead);
        a.append(TestSupport.event("late"));
        a.close();
        assertInstanceOf(StackOverflowError.class, died.get());
        assertEquals(4, a.workerDeadDrops());
        assertEquals(0, a.queued());
        assertEquals(0, a.dropped());
        assertEquals(0, a.delivered());
        assertSettled(a);
    }

    /** A producer parked in BLOCK_WITH_TIMEOUT is woken when the worker dies, and counts as a worker-dead drop instead of waiting for the full timeout. */
    @Test
    void blockedProducerIsReleasedWhenWorkerDies() throws Exception {
        AtomicReference<Throwable> died = new AtomicReference<>();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Appender dying = e -> {
            entered.countDown();
            try {
                gate.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("simulated");
        };
        TestSupport.FakeTicker t = new TestSupport.FakeTicker();
        AsyncAppender a = new AsyncAppender(dying, 1, DropPolicy.BLOCK_WITH_TIMEOUT, Long.MAX_VALUE / 4, t, capturing(died));
        a.append(TestSupport.event("0"));
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        a.append(TestSupport.event("1"));
        Thread producer = new Thread(() -> a.append(TestSupport.event("2")));
        producer.start();
        TestSupport.spinUntil(() -> a.blockedProducers() > 0);
        gate.countDown();
        producer.join();
        a.close();
        assertInstanceOf(AssertionError.class, died.get());
        assertEquals(3, a.workerDeadDrops());
        assertEquals(0, a.dropped());
        assertSettled(a);
    }

    /** A second caller of close waits until the first has drained the queue and closed the delegate. */
    @Test
    void secondCloserWaitsForFirst() throws Exception {
        CountDownLatch closeGate = new CountDownLatch(1);
        AtomicReference<Boolean> delegateClosed = new AtomicReference<>(false);
        Appender slowClose = new Appender() {
            /** Accepts and ignores events. */
            @Override
            public void append(LogEvent e) {
            }

            /** Blocks until the test opens the gate, then marks itself closed. */
            @Override
            public void close() {
                try {
                    closeGate.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                delegateClosed.set(true);
            }
        };
        AsyncAppender a = new AsyncAppender(slowClose, 5, DropPolicy.DROP_NEWEST, 0);
        Thread first = new Thread(a::close);
        first.start();
        TestSupport.spinUntil(a::closing);
        TestSupport.settle(first);
        Thread second = new Thread(a::close);
        second.start();
        assertEquals(Thread.State.WAITING, TestSupport.settle(second), "the second closer must wait, not return");
        assertFalse(delegateClosed.get());
        closeGate.countDown();
        first.join();
        second.join();
        assertTrue(delegateClosed.get());
    }

    /** If the closing thread is interrupted, the delegate is still closed, the unwritten events are counted exactly, and the interrupt flag is restored. */
    @Test
    void interruptedCloseStillClosesDelegateAndCountsExactly() throws Exception {
        Gated g = new Gated();
        AsyncAppender a = new AsyncAppender(g, 5, DropPolicy.DROP_NEWEST, 0);
        jamWorker(a, g);
        a.append(TestSupport.event("1"));
        a.append(TestSupport.event("2"));
        AtomicReference<Boolean> flagAfter = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            a.close();
            flagAfter.set(Thread.currentThread().isInterrupted());
        });
        closer.start();
        TestSupport.settle(closer);
        closer.interrupt();
        TestSupport.spinUntil(a::abandoning);
        g.gate.countDown();
        closer.join();
        assertTrue(g.closed, "delegate must be closed even after an interrupt");
        assertEquals(2, a.abandoned());
        assertEquals(1, a.delivered());
        assertTrue(flagAfter.get(), "interrupt flag must be restored");
        assertSettled(a);
    }

    /** BLOCK_WITH_TIMEOUT waits for the exact remaining time, so a huge fake timeout ends only when the test moves the ticker and wakes the waiter. No real time passes. */
    @Test
    void blockedProducerWakesWhenTickerAdvancesAndWakeIsCalled() throws Exception {
        Gated g = new Gated();
        TestSupport.FakeTicker t = new TestSupport.FakeTicker();
        long hugeWait = 3_600_000_000_000L;
        AsyncAppender a = new AsyncAppender(g, 1, DropPolicy.BLOCK_WITH_TIMEOUT, hugeWait, t, r -> new Thread(r, "w"));
        jamWorker(a, g);
        a.append(TestSupport.event("1"));
        Thread producer = new Thread(() -> a.append(TestSupport.event("2")));
        producer.start();
        TestSupport.spinUntil(() -> a.blockedProducers() > 0);
        t.advance(hugeWait);
        a.wake();
        producer.join();
        assertEquals(1, a.dropped());
        g.gate.countDown();
        a.close();
        assertSettled(a);
    }
}
