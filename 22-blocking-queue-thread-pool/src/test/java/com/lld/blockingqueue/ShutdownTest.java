package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Risk 3: {@code shutdown} versus {@code shutdownNow}, and a bounded {@code awaitTermination}. Latches replace sleeps everywhere.
 *
 * <p>Setup: 1 worker, queue capacity 5. A blocker task holds the worker, tasks "1" and "2" wait in the queue.
 */
class ShutdownTest {

    private final List<String> ran = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicInteger interrupted = new AtomicInteger();

    /** Builds a pool whose single worker is stuck in the blocker task, with two tasks queued behind it. The blocker records an interrupt instead of dying from it. */
    private FixedThreadPool busyPool(Ticker ticker) throws InterruptedException {
        FixedThreadPool pool = new FixedThreadPool(1, 5, RejectionPolicies.abort(), ticker, WaitLoopTest::daemon);
        pool.execute(() -> {
            started.countDown();
            try {
                release.await();
                ran.add("blocker");
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });
        Bounded.await(started, "the worker never started the blocker task");
        pool.execute(() -> ran.add("1"));
        pool.execute(() -> ran.add("2"));
        return pool;
    }

    /** Proves shutdown refuses new work but lets the running task and both queued tasks finish, in order. */
    @Test
    void shutdownFinishesQueuedWorkAndRejectsNewWork() throws Exception {
        FixedThreadPool pool = busyPool(Ticker.system());

        pool.shutdown();
        assertTrue(pool.isShutdown());
        assertFalse(pool.isTerminated()); // the blocker is still running
        assertEquals(0, interrupted.get());
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> pool.execute(() -> ran.add("late")));
        release.countDown();

        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(pool.isTerminated());
        assertEquals(List.of("blocker", "1", "2"), ran);
        assertEquals(0, interrupted.get());
        assertEquals(3, pool.completedTasks());
    }

    /** Proves shutdownNow interrupts the running task and returns exactly the two queued tasks that never started. */
    @Test
    void shutdownNowInterruptsAndReturnsTheTasksThatNeverRan() throws Exception {
        FixedThreadPool pool = busyPool(Ticker.system());

        List<Runnable> neverRan = pool.shutdownNow();

        assertEquals(2, neverRan.size());
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, interrupted.get());
        assertEquals(List.of(), ran); // the blocker was interrupted, tasks 1 and 2 never ran
        // the returned tasks are real and can be run by the caller, in order
        neverRan.forEach(Runnable::run);
        assertEquals(List.of("1", "2"), ran);
    }

    /** Proves awaitTermination gives up on the injected clock: the worker is stuck, the fake clock jumps a whole timeout per read, so it returns false without any real waiting. */
    @Test
    void awaitTerminationTimesOutByTheInjectedTicker() throws Exception {
        FakeTicker ticker = new FakeTicker(TimeUnit.SECONDS.toNanos(5));
        FixedThreadPool pool = busyPool(ticker);
        pool.shutdown();

        assertFalse(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(pool.isTerminated());
        release.countDown();
        ticker.freeze(); // from now on no timeout can expire, so a true answer can only come from the worker really exiting
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the pool did not terminate after the blocker was released");
    }

    /** Proves a second shutdown does nothing, and shutdown on an idle pool terminates it right away. */
    @Test
    void shutdownIsIdempotentAndIdlePoolTerminates() throws Exception {
        FixedThreadPool pool = new FixedThreadPool(2, 2, RejectionPolicies.abort(), Ticker.system(), WaitLoopTest::daemon);

        pool.shutdown();
        pool.shutdown();

        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(pool.shutdownNow().isEmpty());
    }

    /** A queue wrapper that, when asked to close, closes the real queue and then runs a callback BEFORE returning. It lets a test act in the gap between "close" and "drain" of a two-step shutdownNow. */
    private static final class PausingQueue implements BoundedQueue<Runnable> {
        private final BoundedBlockingQueue<Runnable> delegate = new BoundedBlockingQueue<>(5);
        private final CountDownLatch takeReturned = new CountDownLatch(1);
        private volatile Runnable afterClose = () -> { };

        /** Forwards to the real queue. */
        @Override
        public void put(Runnable e) throws InterruptedException {
            delegate.put(e);
        }

        /** Takes from the real queue, and announces that a take has returned (with a task or with the closed signal). */
        @Override
        public Runnable take() throws InterruptedException {
            try {
                return delegate.take();
            } finally {
                if (afterCloseRan) {
                    takeReturned.countDown();
                }
            }
        }

        private volatile boolean afterCloseRan;

        /** Forwards to the real queue. */
        @Override
        public boolean offer(Runnable e) {
            return delegate.offer(e);
        }

        /** Forwards to the real queue. */
        @Override
        public boolean offer(Runnable e, long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.offer(e, timeout, unit);
        }

        /** Forwards to the real queue. */
        @Override
        public Runnable poll() {
            return delegate.poll();
        }

        /** Forwards to the real queue. */
        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.poll(timeout, unit);
        }

        /** Forwards to the real queue. */
        @Override
        public int size() {
            return delegate.size();
        }

        /** Forwards to the real queue. */
        @Override
        public int capacity() {
            return delegate.capacity();
        }

        /** Closes the real queue, then runs the callback while the caller is still "between close and drain". */
        @Override
        public void close() {
            delegate.close();
            afterCloseRan = true;
            afterClose.run();
        }

        /** Forwards to the real queue. */
        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        /** Forwards to the real queue. */
        @Override
        public List<Runnable> drainAll() {
            return delegate.drainAll();
        }

        /** Forwards to the real queue's single-step version. */
        @Override
        public List<Runnable> closeAndDrain() {
            return delegate.closeAndDrain();
        }
    }

    /**
     * Proves shutdownNow closes and drains in ONE step. A two-step version (close, then drain) lets a busy worker take a queued task in between and run it after shutdownNow was called.
     *
     * <p>The test forces that gap: the wrapper's {@code close()} releases the blocker, and waits until the worker has taken again, before returning. A correct pool never calls {@code close()} here, so the callback never runs and the queued task is simply returned.
     */
    @Test
    void shutdownNowClosesAndDrainsInOneStep() throws Exception {
        PausingQueue queue = new PausingQueue();
        FixedThreadPool pool = new FixedThreadPool(1, queue, RejectionPolicies.abort(), Ticker.system(), WaitLoopTest::daemon);
        pool.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });
        Bounded.await(started, "the worker never started the blocker task");
        pool.execute(() -> ran.add("queued"));
        queue.afterClose = () -> {
            release.countDown(); // the worker finishes the blocker and goes back to take()
            try {
                Bounded.await(queue.takeReturned, "the worker never took again");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        List<Runnable> neverRan = pool.shutdownNow();
        release.countDown();

        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the pool did not terminate");
        assertEquals(1, neverRan.size(), "the queued task must be handed back");
        assertEquals(List.of(), ran, "the queued task must not run after shutdownNow");
    }

    /** Proves a task that throws an Error (such as an AssertionError under -ea) is counted as failed and does not kill the only worker: the next task still runs. */
    @Test
    void anErrorInATaskDoesNotKillTheOnlyWorker() throws Exception {
        FixedThreadPool pool = new FixedThreadPool(1, 4, RejectionPolicies.abort(), Ticker.system(), WaitLoopTest::daemon);
        CountDownLatch ranAfter = new CountDownLatch(1);
        pool.execute(() -> {
            throw new AssertionError("boom");
        });
        pool.execute(ranAfter::countDown);

        Bounded.await(ranAfter, "the second task never ran: the Error killed the only worker");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the pool did not terminate");
        assertEquals(1, pool.failedTasks());
    }

    /** Proves a task that throws is counted as failed, and the worker survives to run the next task. */
    @Test
    void aFailingTaskDoesNotKillItsWorker() throws Exception {
        FixedThreadPool pool = new FixedThreadPool(1, 4, RejectionPolicies.abort(), Ticker.system(), WaitLoopTest::daemon);
        pool.execute(() -> {
            throw new IllegalStateException("boom");
        });
        pool.execute(() -> ran.add("after"));
        pool.shutdown();

        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, pool.failedTasks());
        assertEquals(List.of("after"), ran);
    }
}
