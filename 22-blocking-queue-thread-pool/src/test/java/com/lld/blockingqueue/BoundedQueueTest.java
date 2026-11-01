package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Behaviour of the bounded queue: order, limits, timeouts (with a fake clock), interrupts, close, and a differential test against a plain {@code ArrayDeque}. */
class BoundedQueueTest {

    /** Proves items come out in the order they went in, including after the ring buffer wraps around. */
    @Test
    void keepsFifoOrderAcrossWrapAround() throws Exception {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        for (int round = 0; round < 5; round++) {
            q.put(round * 10);
            q.put(round * 10 + 1);
            assertEquals(round * 10, q.take());
            assertEquals(round * 10 + 1, q.take());
        }
        assertEquals(0, q.size());
    }

    /** Proves a non-waiting offer refuses when full and poll returns null when empty. */
    @Test
    void offerAndPollNeverWait() {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1);

        assertNull(q.poll());
        assertTrue(q.offer("a"));
        assertFalse(q.offer("b"));
        assertEquals("a", q.poll());
    }

    /** Proves bad input is refused: null items and capacity below one. */
    @Test
    void rejectsNullAndBadCapacity() {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1);

        assertThrows(NullPointerException.class, () -> q.offer(null));
        assertThrows(NullPointerException.class, () -> q.put(null));
        assertThrows(IllegalArgumentException.class, () -> new BoundedBlockingQueue<String>(0));
    }

    /** Proves a timed poll gives up when the fake clock says the time is over: each clock read advances by the whole 2-second timeout, so the first check already sees the time as over and the code never reaches a real wait. */
    @Test
    void timedPollExpiresByTheInjectedTicker() throws Exception {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(TimeUnit.SECONDS.toNanos(2)), QueueHooks.NONE);

        assertNull(q.poll(2, TimeUnit.SECONDS));
    }

    /** Proves a timed offer on a full queue gives up by the fake clock, and leaves the queue unchanged. */
    @Test
    void timedOfferExpiresByTheInjectedTicker() throws Exception {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(TimeUnit.SECONDS.toNanos(2)), QueueHooks.NONE);
        q.put("a");

        assertFalse(q.offer("b", 2, TimeUnit.SECONDS));
        assertEquals(1, q.size());
    }

    /** Proves a timed poll returns at once when an item is there, even with a zero timeout. */
    @Test
    void timedPollReturnsAvailableItemImmediately() throws Exception {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(0), QueueHooks.NONE);
        q.put("a");

        assertEquals("a", q.poll(0, TimeUnit.SECONDS));
    }

    /** Proves an interrupted waiting taker gets InterruptedException and the queue is still usable afterwards. */
    @Test
    void interruptedTakerThrowsAndQueueSurvives() throws Exception {
        Semaphore asleep = new Semaphore(0);
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(0), QueueHooks.of(s -> asleep.release(), s -> { }));
        ExecutorService pool = Executors.newSingleThreadExecutor(WaitLoopTest::daemon);
        try {
            Thread[] taker = new Thread[1];
            CountDownLatch started = new CountDownLatch(1);
            Future<Boolean> f = pool.submit(() -> {
                taker[0] = Thread.currentThread();
                started.countDown();
                try {
                    q.take();
                    return false;
                } catch (InterruptedException e) {
                    return true;
                }
            });
            Bounded.await(started, "the taker thread never started");
            Bounded.acquire(asleep, 1, "the taker never went to sleep");
            taker[0].interrupt();

            assertTrue(Bounded.get(f, "the interrupted taker never woke up"));
            q.put("still works");
            assertEquals("still works", q.take());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves close wakes a sleeping taker with QueueClosedException, and wakes a sleeping putter too. */
    @Test
    void closeWakesBothKindsOfWaiter() throws Exception {
        Semaphore asleep = new Semaphore(0);
        BoundedBlockingQueue<String> empty = new BoundedBlockingQueue<>(1, new FakeTicker(0), QueueHooks.of(s -> asleep.release(), s -> { }));
        BoundedBlockingQueue<String> full = new BoundedBlockingQueue<>(1, new FakeTicker(0), QueueHooks.of(s -> asleep.release(), s -> { }));
        full.put("x");
        ExecutorService pool = Executors.newCachedThreadPool(WaitLoopTest::daemon);
        try {
            Future<?> taker = pool.submit(() -> empty.take());
            Future<?> putter = pool.submit(() -> {
                full.put("y");
                return null;
            });
            Bounded.acquire(asleep, 2, "the taker and the putter never both went to sleep");
            empty.close();
            full.close();

            assertEquals(QueueClosedException.class, Bounded.failureOf(taker, "close() did not wake the sleeping taker").getClass());
            assertEquals(QueueClosedException.class, Bounded.failureOf(putter, "close() did not wake the sleeping putter").getClass());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves a closed queue still hands out what it holds, then reports closed, and refuses new items. */
    @Test
    void closedQueueDrainsThenRefuses() throws Exception {
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(2);
        q.put("a");
        q.close();
        q.close(); // second close is harmless

        assertTrue(q.isClosed());
        assertThrows(QueueClosedException.class, () -> q.put("b"));
        assertThrows(QueueClosedException.class, () -> q.offer("b"));
        assertEquals("a", q.take());
        assertThrows(QueueClosedException.class, q::take);
        assertNull(q.poll(1, TimeUnit.SECONDS));
    }

    /** Differential test: 5,000 random single-threaded operations must match a plain ArrayDeque with the same capacity rule. */
    @Test
    void matchesNaiveReferenceModel() {
        Random random = new Random(42);
        int capacity = 5;
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(capacity);
        Queue<Integer> model = new ArrayDeque<>();
        for (int i = 0; i < 5_000; i++) {
            if (random.nextBoolean()) {
                boolean modelAccepted = model.size() < capacity;
                if (modelAccepted) {
                    model.add(i);
                }
                assertEquals(modelAccepted, q.offer(i));
            } else {
                assertEquals(model.poll(), q.poll());
            }
            assertEquals(model.size(), q.size());
        }
    }

    /**
     * Proves the timed wait really waits, and for the right number of rounds. The fake clock moves 1 ms per read and the timeout is 3 ms, so the loop must go to sleep exactly twice (with 2 ms then 1 ms left) and give up on the third check.
     *
     * <p>Real time: the sleep itself ({@code awaitNanos}) uses the real clock, so this test spends about 3 ms for real. The Ticker only decides WHETHER to wait and how much is left, it does not make {@code awaitNanos} faster. Catches {@code awaitNanos(remaining)} changed to {@code await()} (the test hangs, then fails on its 10 s limit) and {@code <= 0} changed to {@code < 0} (a third wait appears).
     */
    @Test
    void timedWaitSleepsOncePerRemainingSliceOfTime() throws Exception {
        AtomicInteger sleeps = new AtomicInteger();
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(TimeUnit.MILLISECONDS.toNanos(1)), QueueHooks.of(s -> sleeps.incrementAndGet(), s -> { }));

        assertNull(q.poll(3, TimeUnit.MILLISECONDS));
        assertEquals(2, sleeps.get());
        q.put("full");
        sleeps.set(0);
        assertFalse(q.offer("x", 3, TimeUnit.MILLISECONDS));
        assertEquals(2, sleeps.get());
    }

    /** Proves a taken item's slot is cleared, so the queue does not keep finished tasks alive (a memory leak otherwise). */
    @Test
    void takenSlotsAreClearedSoNothingLeaks() throws Exception {
        BoundedBlockingQueue<Object> q = new BoundedBlockingQueue<>(3);
        q.put(new Object());
        q.put(new Object());
        q.put(new Object());
        assertEquals(3, q.occupiedSlots());
        q.take();
        q.poll();
        q.drainAll();

        assertEquals(0, q.occupiedSlots());
    }

    /** Proves closeAndDrain closes and empties in one step: the closed queue is empty, and a taker gets QueueClosedException. */
    @Test
    void closeAndDrainClosesAndEmptiesTogether() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        q.offer(1);
        q.offer(2);

        assertEquals(List.of(1, 2), q.closeAndDrain());
        assertTrue(q.isClosed());
        assertEquals(0, q.size());
        assertThrows(QueueClosedException.class, q::take);
        assertThrows(QueueClosedException.class, () -> q.put(3));
    }

    /** Proves drainAll empties the queue oldest first. */
    @Test
    void drainAllReturnsOldestFirst() {
        BoundedBlockingQueue<Integer> q = new BoundedBlockingQueue<>(3);
        q.offer(1);
        q.offer(2);
        List<Integer> drained = q.drainAll();

        assertEquals(new ArrayList<>(List.of(1, 2)), drained);
        assertEquals(0, q.size());
    }
}
