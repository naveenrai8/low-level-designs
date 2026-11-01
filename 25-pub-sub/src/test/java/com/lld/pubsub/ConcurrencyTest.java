package com.lld.pubsub;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Multi-thread tests for per-key ordering with parallel consumers. Every test has a timeout so a broken mutation fails instead of hanging. */
class ConcurrencyTest {

    /** Builds a broker with topic "t" and group "g" whose ack deadline never expires. */
    private InMemoryPubSub broker() {
        InMemoryPubSub ps = new InMemoryPubSub(() -> 0L);
        ps.createTopic("t");
        ps.subscribe("t", "g", new SubscriptionConfig(3, Long.MAX_VALUE / 4, StartAt.EARLIEST));
        return ps;
    }

    /**
     * Risk 3, deterministic proof of parallelism: with 4 keys and a 4-party barrier inside the handler, the barrier only opens if 4 different keys run at the same moment. If the pool serialised everything, the barrier would time out and fail the test.
     */
    @Test
    @Timeout(20)
    void differentKeysRunInParallel() throws Exception {
        InMemoryPubSub ps = broker();
        CyclicBarrier barrier = new CyclicBarrier(4);
        AtomicInteger done = new AtomicInteger();
        CountDownLatch all = new CountDownLatch(4);
        for (int k = 0; k < 4; k++) {
            ps.publish("t", "key" + k, "p");
        }
        try (ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            barrier.await(10, TimeUnit.SECONDS);
            done.incrementAndGet();
            all.countDown();
        }, 4)) {
            assertTrue(all.await(15, TimeUnit.SECONDS));
        }
        assertEquals(4, done.get());
    }

    /**
     * Risk 3, deterministic proof of serial same-key work: the first message of key "a" parks inside the handler on a latch while other workers are idle and free. The second "a" message must not start. We check that through a same-thread probe of the broker, then release.
     */
    @Test
    @Timeout(20)
    void sameKeyWaitsWhileFirstIsRunning() throws Exception {
        InMemoryPubSub ps = broker();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(2);
        ps.publish("t", "a", "first");
        ps.publish("t", "a", "second");
        try (ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            order.add(m.offset());
            if (m.offset() == 0) {
                inside.countDown();
                release.await();
            }
            finished.countDown();
        }, 4)) {
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            // Message 0 is in flight and holds key "a"; nothing else may be handed out.
            assertTrue(ps.poll("t", "g").isEmpty());
            assertEquals(List.of(0L), order);
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(0L, 1L), order);
    }

    /**
     * Risk 3, exact totals: 8 workers, 16 keys x 50 messages. All messages are published before the workers start. Per key we record the offsets seen, which must be strictly increasing, and no two messages of one key may ever overlap in time.
     */
    @Test
    @Timeout(30)
    void perKeyOrderHoldsWithManyWorkers() throws Exception {
        InMemoryPubSub ps = broker();
        int keys = 16;
        int perKey = 50;
        int total = keys * perKey;
        Map<String, List<Long>> seen = new HashMap<>();
        Map<String, AtomicInteger> running = new HashMap<>();
        for (int k = 0; k < keys; k++) {
            seen.put("k" + k, new ArrayList<>());
            running.put("k" + k, new AtomicInteger());
        }
        AtomicInteger overlaps = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(total);
        for (int i = 0; i < perKey; i++) {
            for (int k = 0; k < keys; k++) {
                ps.publish("t", "k" + k, "p");
            }
        }
        try (ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            AtomicInteger r = running.get(m.key());
            if (r.incrementAndGet() != 1) {
                overlaps.incrementAndGet();
            }
            // The list is guarded by its own monitor; the running counter above is what detects overlap.
            List<Long> list = seen.get(m.key());
            synchronized (list) {
                list.add(m.offset());
            }
            r.decrementAndGet();
            finished.countDown();
        }, 8)) {
            assertTrue(finished.await(20, TimeUnit.SECONDS));
        }
        assertEquals(0, overlaps.get());
        int count = 0;
        for (List<Long> list : seen.values()) {
            assertEquals(perKey, list.size());
            for (int i = 1; i < list.size(); i++) {
                assertTrue(list.get(i) > list.get(i - 1), "out of order: " + list);
            }
            count += list.size();
        }
        assertEquals(total, count);
    }

    /**
     * Retries keep the order too: the first message of a key fails once, and its successor must still wait and run after it. Exact total: every message acked, none dead.
     */
    @Test
    @Timeout(20)
    void retryKeepsKeyOrder() throws Exception {
        InMemoryPubSub ps = broker();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failedOnce = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            ps.publish("t", "same", "m" + i);
        }
        try (ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            if (m.offset() == 1 && failedOnce.getAndIncrement() == 0) {
                log.add("fail1");
                throw new IllegalStateException("boom");
            }
            log.add("ok" + m.offset());
            finished.countDown();
        }, 4)) {
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of("ok0", "fail1", "ok1", "ok2", "ok3", "ok4"), log);
        assertTrue(ps.deadLetters("t", "g").isEmpty());
    }

    /**
     * Many publishers and many consumers: every message is processed exactly once (no failures injected), nothing lost, nothing duplicated.
     */
    @Test
    @Timeout(30)
    void concurrentPublishersLoseNothing() throws Exception {
        InMemoryPubSub ps = broker();
        int publishers = 4;
        int each = 250;
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger dupes = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(publishers * each);
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < publishers; p++) {
            int id = p;
            Thread t = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    ps.publish("t", "k" + (id * each + i) % 10, "p");
                }
            });
            threads.add(t);
            t.start();
        }
        try (ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            if (!seen.add(m.offset())) {
                dupes.incrementAndGet();
            }
            finished.countDown();
        }, 6)) {
            gate.countDown();
            assertTrue(finished.await(20, TimeUnit.SECONDS));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(0, dupes.get());
        assertEquals(publishers * each, seen.size());
        assertEquals(0, ps.lag("t", "g"));
    }

    /** Item 1: close() must return even if a handler is parked, and the shutdown must not dead-letter it: the message is deliverable again (attempt 2) and the DLQ is empty. */
    @Test
    @Timeout(10)
    void closeReturnsAndDoesNotBurnAnAttempt() throws Exception {
        InMemoryPubSub ps = broker();
        CountDownLatch inside = new CountDownLatch(1);
        ps.publish("t", "k", "p");
        ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            inside.countDown();
            new CountDownLatch(1).await();
        }, 1);
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        pool.close();
        assertEquals(2, ps.poll("t", "g").orElseThrow().attempt(), "shutdown nacks once, so the retry is attempt 2");
        assertTrue(ps.deadLetters("t", "g").isEmpty());
    }

    /** Item 2: a handler that throws an Error (here AssertionError) once must not strand its key: the message is retried and its successor still runs. */
    @Test
    @Timeout(10)
    void handlerErrorDoesNotStrandTheKey() throws Exception {
        InMemoryPubSub ps = broker();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger thrown = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(2);
        ps.publish("t", "k", "p0");
        ps.publish("t", "k", "p1");
        try (ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            if (m.offset() == 0 && thrown.getAndIncrement() == 0) {
                log.add("err0");
                throw new AssertionError("boom");
            }
            log.add("ok" + m.offset());
            finished.countDown();
        }, 1)) {
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("err0", "ok0", "ok1"), log);
    }

    /** Item 6: the pool refuses nonsense up front: zero threads, or a group that does not exist. */
    @Test
    @Timeout(10)
    void poolValidatesItsArguments() {
        InMemoryPubSub ps = broker();
        assertThrows(IllegalArgumentException.class, () -> new ConsumerPool(ps, "t", "g", m -> { }, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConsumerPool(ps, "t", "nope", m -> { }, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConsumerPool(ps, "nope", "g", m -> { }, 1));
    }

    /** Item 3: on a quiet topic, a blocked take() wakes by itself when the ack deadline of an in-flight message passes. Real 20 ms deadline, no sleeping in the test: take() just waits for its timed wake-up. */
    @Test
    @Timeout(10)
    void takeWakesWhenAnAckDeadlinePasses() throws Exception {
        InMemoryPubSub ps = new InMemoryPubSub(Ticker.system());
        ps.createTopic("t");
        ps.subscribe("t", "g", new SubscriptionConfig(3, 20_000_000L, StartAt.EARLIEST));
        ps.publish("t", "k", "p");
        assertEquals(1, ps.poll("t", "g").orElseThrow().attempt());
        assertEquals(2, ps.take("t", "g").attempt());
    }

    /** Item 3 and 7: with a fake ticker, an idle worker picks up a redelivery as soon as the deadline passes and the test calls wake(); the zombie handler's late ack is refused and counted. */
    @Test
    @Timeout(10)
    void idleWorkerTakesOverAfterDeadlineAndLateAckIsCounted() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        InMemoryPubSub ps = new InMemoryPubSub(clock::get);
        ps.createTopic("t");
        ps.subscribe("t", "g", new SubscriptionConfig(3, 100, StartAt.EARLIEST));
        ps.publish("t", "k", "p");
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ConsumerPool pool = new ConsumerPool(ps, "t", "g", m -> {
            if (calls.getAndIncrement() == 0) {
                firstInside.countDown();
                releaseFirst.await();
            } else {
                secondDone.countDown();
            }
        }, 2);
        try {
            assertTrue(firstInside.await(5, TimeUnit.SECONDS));
            clock.addAndGet(100);
            ps.wake("t");
            assertTrue(secondDone.await(5, TimeUnit.SECONDS), "the idle worker must take the redelivery");
            releaseFirst.countDown();
            // Wait (bounded by @Timeout) until the zombie's late ack has been refused, so close() cannot interrupt it first.
            while (pool.refusedAcks() < 1) {
                Thread.onSpinWait();
            }
        } finally {
            pool.close();
        }
        assertEquals(1, pool.refusedAcks());
        assertEquals(0, ps.lag("t", "g"));
    }
}
