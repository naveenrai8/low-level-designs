package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lld.blockingqueue.QueueHooks.Side;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Risk 2: lost signals. A signal only helps threads that are already waiting, and {@code signal()} wakes just one of them. These tests show a lost wakeup with one shared waiting room, and show that the two-room design handles the identical scenario.
 *
 * <p>The scenario, capacity 1: takers T1 then T2 wait on the empty queue (T1 first, forced). A producer puts "a", which wakes T1. T1 cannot run yet, because the producer still holds the lock. The producer then (inside the {@code afterSignal} hook, using the re-entrant lock) puts "b", finds the queue full, and waits. Only now does T1 run: it takes "a" and signals.
 * <ul>
 * <li>One room: the room holds [T2, producer]. The signal wakes the longest waiter, T2, a taker. T2 sees an empty queue and sleeps again. The producer is never woken: deadlock.
 * <li>Two rooms: T1's take signals {@code notFull}, which only holds putters, so the producer wakes. It puts "b", signals {@code notEmpty}, and T2 gets "b".
 * </ul>
 *
 * <p>Nothing is left to scheduling luck: the producer's second put happens inside the first put's lock hold, so it always comes before T1 can run.
 */
class LostSignalTest {

    /** A queue under test plus the semaphore that counts how many times a thread went to sleep. */
    private record Rig(SimpleQueue<String> queue, Semaphore sleeps) { }

    /**
     * Builds a queue whose first "wake a taker" signal immediately triggers the producer's second put (re-entrantly, under the same lock hold). That re-entrant {@code await} is the point of the trick: it makes the producer wait BEFORE the woken taker can take the lock.
     */
    private static Rig rig(Function<QueueHooks, SimpleQueue<String>> factory) {
        Semaphore sleeps = new Semaphore(0);
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<SimpleQueue<String>> ref = new AtomicReference<>();
        QueueHooks hooks = QueueHooks.of(side -> sleeps.release(), side -> {
            if (side == Side.NOT_EMPTY && armed.compareAndSet(true, false)) {
                try {
                    ref.get().put("b");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        SimpleQueue<String> q = factory.apply(hooks);
        ref.set(q);
        armed.set(true);
        return new Rig(q, sleeps);
    }

    /** Proves the BUG: with one shared room the producer is stranded forever. "Stuck" is detected by counting sleeps (four of them), not by sleeping. */
    @Test
    void oneSharedRoomLosesTheSignalAndStrandsTheProducer() throws Exception {
        Rig rig = rig(h -> new OneConditionQueue(1, h));
        ExecutorService pool = Executors.newCachedThreadPool(r -> WaitLoopTest.daemon(r));
        try {
            Future<String> t1 = pool.submit(rig.queue()::take);
            Bounded.acquire(rig.sleeps(), 1, "T1 never went to sleep");
            Future<String> t2 = pool.submit(rig.queue()::take);
            Bounded.acquire(rig.sleeps(), 1, "T2 never went to sleep");
            Future<?> producer = pool.submit(() -> {
                rig.queue().put("a");
                return null;
            });
            // sleeps 3 and 4: the producer waits on the full queue, then T1's signal wakes T2 (a taker) and T2 goes back to sleep
            Bounded.acquire(rig.sleeps(), 2, "expected the producer to wait and T2 to be woken and sleep again");

            assertEquals("a", Bounded.get(t1, "T1 never got its item"));
            assertFalse(producer.isDone(), "the producer should be stuck: its wakeup went to a taker");
            assertFalse(t2.isDone());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves the FIX: the identical scenario on the real two-room queue completes, and every item is delivered exactly once. */
    @Test
    void twoRoomsDeliverTheSignalToTheRightKindOfThread() throws Exception {
        Rig rig = rig(h -> SimpleQueue.of(new BoundedBlockingQueue<>(1, new FakeTicker(0), h)));
        ExecutorService pool = Executors.newCachedThreadPool(r -> WaitLoopTest.daemon(r));
        try {
            Future<String> t1 = pool.submit(rig.queue()::take);
            Bounded.acquire(rig.sleeps(), 1, "T1 never went to sleep");
            Future<String> t2 = pool.submit(rig.queue()::take);
            Bounded.acquire(rig.sleeps(), 1, "T2 never went to sleep");
            Future<?> producer = pool.submit(() -> {
                rig.queue().put("a");
                return null;
            });

            Bounded.get(producer, "the producer was never woken: the notFull signal was lost");
            Set<String> got = new HashSet<>();
            got.add(Bounded.get(t1, "T1 never got an item"));
            got.add(Bounded.get(t2, "T2 never got an item: the notEmpty signal after the second put was lost"));
            assertEquals(Set.of("a", "b"), got);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves a signal sent before anyone waits is not needed: the condition is state ({@code count}), so a late taker sees the item and never sleeps at all. */
    @Test
    void putBeforeTakeNeverWaitsSoNothingCanBeLost() throws Exception {
        AtomicInteger sleeps = new AtomicInteger();
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(2, new FakeTicker(0), QueueHooks.of(side -> sleeps.incrementAndGet(), side -> { }));
        q.put("early"); // the signal goes to an empty waiting room and is lost, which is harmless

        assertEquals("early", q.take());
        assertEquals(0, sleeps.get());
        assertNull(q.poll());
    }

    /** Proves close() wakes EVERY sleeping taker (signalAll): with two takers asleep, one close() frees both, and both get QueueClosedException. A plain signal() would free only one. */
    @Test
    void closeWakesEveryTakerNotJustOne() throws Exception {
        Semaphore sleeps = new Semaphore(0);
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(0), QueueHooks.of(side -> sleeps.release(), side -> { }));
        ExecutorService pool = Executors.newCachedThreadPool(r -> WaitLoopTest.daemon(r));
        try {
            Future<String> t1 = pool.submit(q::take);
            Future<String> t2 = pool.submit(q::take);
            Bounded.acquire(sleeps, 2, "the two takers never both went to sleep");
            q.close();

            assertEquals(QueueClosedException.class, Bounded.failureOf(t1, "taker 1 was not woken by close()").getClass());
            assertEquals(QueueClosedException.class, Bounded.failureOf(t2, "taker 2 was not woken by close() (signal instead of signalAll?)").getClass());
        } finally {
            pool.shutdownNow();
        }
    }
}
