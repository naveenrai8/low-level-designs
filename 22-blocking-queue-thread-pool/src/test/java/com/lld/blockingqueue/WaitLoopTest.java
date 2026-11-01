package com.lld.blockingqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Risk 1: every wait must be in a {@code while} loop. These tests force a stolen or shared wakeup with hooks and a semaphore (no sleeping) and show that the {@code if} version breaks while the {@code while} version survives the very same steps.
 *
 * <p>How the interleaving is forced: {@code beforeAwait} runs under the lock just before a thread sleeps, so once the test has seen it, any thread that later gets the lock knows the sleeper really is asleep.
 */
class WaitLoopTest {

    /** Starts a thread running {@code take()} on the queue and returns its future. */
    private static Future<String> startTaker(ExecutorService pool, SimpleQueue<String> q) {
        return pool.submit(q::take);
    }

    /** Builds the hooks: every "about to sleep" releases a permit, and an armed steal removes the item from inside the put, before the woken taker can run. */
    private static QueueHooks hooks(Semaphore sleeps, AtomicBoolean steal, AtomicReference<SimpleQueue<String>> queue, AtomicReference<String> stolen) {
        return QueueHooks.of(side -> sleeps.release(), side -> {
            if (steal.compareAndSet(true, false)) {
                stolen.set(queue.get().poll());
            }
        });
    }

    /** Runs one taker, makes a thief steal the item right after the put's signal, and returns what the taker got right after that wakeup (broken) or after a second put (fixed). */
    private String stolenWakeup(Function<QueueHooks, SimpleQueue<String>> factory, boolean expectLoop) throws Exception {
        Semaphore sleeps = new Semaphore(0);
        AtomicBoolean steal = new AtomicBoolean();
        AtomicReference<SimpleQueue<String>> ref = new AtomicReference<>();
        AtomicReference<String> stolen = new AtomicReference<>();
        SimpleQueue<String> q = factory.apply(hooks(sleeps, steal, ref, stolen));
        ref.set(q);
        ExecutorService pool = Executors.newCachedThreadPool(r -> daemon(r));
        try {
            Future<String> taker = startTaker(pool, q);
            Bounded.acquire(sleeps, 1, "the taker never went to sleep"); // our put below can only run once it really sleeps
            steal.set(true);
            q.put("first"); // signals the taker, then the hook steals "first" before the taker can wake
            assertEquals("first", stolen.get());
            if (expectLoop) {
                Bounded.acquire(sleeps, 1, "the taker did not re-check the condition and sleep again after its item was stolen (an if instead of a while?)");
                q.put("second");
            }
            return Bounded.get(taker, "the taker never finished: its wakeup was lost or it was stuck");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves the BUG: with {@code if}, a taker whose item was stolen carries on and returns {@code null} from an empty queue. */
    @Test
    void ifWaitReturnsGarbageWhenTheWakeupIsStolen() throws Exception {
        String got = stolenWakeup(h -> new IfWaitQueue(false, h), false);

        assertNull(got);
    }

    /** Proves the FIX on the real queue: the same steps make the taker re-check, sleep again, and return the next item. */
    @Test
    void whileWaitSurvivesTheStolenWakeup() throws Exception {
        String got = stolenWakeup(h -> SimpleQueue.of(new BoundedBlockingQueue<>(4, new FakeTicker(0), h)), true);

        assertEquals("second", got);
    }

    /** Proves the FIXED version of the test class also survives, so the only difference between bug and fix is if versus while. */
    @Test
    void whileWaitInTheTestClassAlsoSurvives() throws Exception {
        String got = stolenWakeup(h -> new IfWaitQueue(true, h), true);

        assertEquals("second", got);
    }

    /** Runs two takers, one put with signalAll (both wake), then returns the two results. When {@code loop} is true a second put is made after both have re-checked. */
    private Set<String> twoTakersOnePut(boolean loop) throws Exception {
        Semaphore sleeps = new Semaphore(0);
        IfWaitQueue q = new IfWaitQueue(loop, QueueHooks.of(side -> sleeps.release(), side -> { }));
        ExecutorService pool = Executors.newCachedThreadPool(r -> daemon(r));
        try {
            Future<String> a = startTaker(pool, q);
            Future<String> b = startTaker(pool, q);
            Bounded.acquire(sleeps, 2, "the two takers never both went to sleep");
            q.put("x"); // signalAll: both wake up for one item
            if (loop) {
                Bounded.acquire(sleeps, 1, "the loser did not re-check and sleep again (an if instead of a while?)");
                q.put("y");
            }
            Set<String> results = new HashSet<>();
            results.add(String.valueOf(Bounded.get(a, "taker A never finished")));
            results.add(String.valueOf(Bounded.get(b, "taker B never finished")));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves the BUG with two takers: one item wakes both, and the loser of the race gets {@code null}. */
    @Test
    void ifWaitLetsTheSecondWokenTakerReadAnEmptyQueue() throws Exception {
        Set<String> results = twoTakersOnePut(false);

        assertTrue(results.contains("null"), "one taker should have received null: " + results);
        assertTrue(results.contains("x"));
    }

    /** Proves the FIX with two takers: the loser sleeps again and gets the next item, so each item is delivered exactly once. */
    @Test
    void whileWaitDeliversEachItemExactlyOnce() throws Exception {
        Set<String> results = twoTakersOnePut(true);

        assertEquals(Set.of("x", "y"), results);
    }

    /** Proves the put side too: with capacity 1, a thief refills the slot right after the taker's signal, and the woken putter must re-check, sleep again, and never write into a full queue. */
    @Test
    void putWaitsInAWhileLoopToo() throws Exception {
        Semaphore sleeps = new Semaphore(0);
        AtomicBoolean steal = new AtomicBoolean();
        AtomicReference<BoundedBlockingQueue<String>> ref = new AtomicReference<>();
        QueueHooks hooks = QueueHooks.of(side -> sleeps.release(), side -> {
            if (side == QueueHooks.Side.NOT_FULL && steal.compareAndSet(true, false)) {
                assertTrue(ref.get().offer("thief")); // refills the slot the taker just freed
            }
        });
        BoundedBlockingQueue<String> q = new BoundedBlockingQueue<>(1, new FakeTicker(0), hooks);
        ref.set(q);
        q.put("a");
        ExecutorService pool = Executors.newCachedThreadPool(r -> daemon(r));
        try {
            Future<?> putter = pool.submit(() -> {
                q.put("b");
                return null;
            });
            Bounded.acquire(sleeps, 1, "the putter never went to sleep on the full queue");
            steal.set(true);
            assertEquals("a", q.take()); // frees the slot, signals notFull, then the thief refills it
            Bounded.acquire(sleeps, 1, "the putter did not re-check and sleep again after its slot was stolen (an if instead of a while?)");
            assertEquals(1, q.size());
            assertEquals("thief", q.take()); // frees the slot again, now the putter may go
            Bounded.get(putter, "the putter never finished");

            assertEquals("b", q.take());
            assertEquals(0, q.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Makes a daemon thread so a failing test can never keep the JVM alive. */
    static Thread daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    }
}
