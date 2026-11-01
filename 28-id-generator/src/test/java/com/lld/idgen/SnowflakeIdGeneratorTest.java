package com.lld.idgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves the Snowflake generator: ordering, sequence overflow, clock step-back, the CAS race, and thread safety. */
class SnowflakeIdGeneratorTest {

    private static final long EPOCH = 1_700_000_000_000L;
    private static final long START = EPOCH + 1_000;

    /** Builds a generator on a fake clock with node 7 and the given step-back tolerance. */
    private static SnowflakeIdGenerator gen(FakeClock clock, Waiter waiter, long tolerance) {
        return new SnowflakeIdGenerator(clock, waiter, 7, EPOCH, tolerance);
    }

    /** The first ID carries the clock's time, the node, and sequence 0. */
    @Test
    void firstIdDecodesToClockNodeAndZeroSequence() {
        long id = gen(new FakeClock(START), Waiter.spin(), 0).nextId();
        assertEquals(1_000, SnowflakeLayout.timestamp(id));
        assertEquals(7, SnowflakeLayout.node(id));
        assertEquals(0, SnowflakeLayout.sequence(id));
    }

    /** Inside one frozen millisecond the sequence counts 0, 1, 2, ... and IDs strictly increase. */
    @Test
    void sequenceCountsUpInsideOneMillisecond() {
        SnowflakeIdGenerator g = gen(new FakeClock(START), Waiter.spin(), 0);
        long prev = g.nextId();
        for (int i = 1; i <= 100; i++) {
            long id = g.nextId();
            assertTrue(id > prev);
            assertEquals(i, SnowflakeLayout.sequence(id));
            prev = id;
        }
    }

    /** When the clock moves forward the sequence restarts at 0. */
    @Test
    void sequenceResetsWhenClockMoves() {
        FakeClock clock = new FakeClock(START);
        SnowflakeIdGenerator g = gen(clock, Waiter.spin(), 0);
        g.nextId();
        g.nextId();
        clock.advance(1);
        long id = g.nextId();
        assertEquals(1_001, SnowflakeLayout.timestamp(id));
        assertEquals(0, SnowflakeLayout.sequence(id));
    }

    /** All 4096 sequence numbers are used without waiting; the 4097th call waits exactly once and lands in the next millisecond with sequence 0. */
    @Test
    void sequenceOverflowWaitsForNextMillisecond() {
        FakeClock clock = new FakeClock(START);
        FakeClock.CountingWaiter waiter = clock.advancingWaiter();
        SnowflakeIdGenerator g = gen(clock, waiter, 0);
        long last = 0;
        for (int i = 0; i < 4096; i++) {
            last = g.nextId();
        }
        assertEquals(0, waiter.calls());
        assertEquals(4095, SnowflakeLayout.sequence(last));
        long next = g.nextId();
        assertEquals(1, waiter.calls());
        assertEquals(1_001, SnowflakeLayout.timestamp(next));
        assertEquals(0, SnowflakeLayout.sequence(next));
        assertTrue(next > last);
    }

    /** A waiter that does nothing keeps the generator waiting: it never reuses a sequence or borrows the next millisecond on its own. The clock only moves on the 5th pause, so exactly 5 pauses happen. */
    @Test
    void generatorKeepsWaitingUntilClockActuallyMoves() {
        FakeClock clock = new FakeClock(START);
        int[] pauses = {0};
        Waiter lazy = () -> {
            pauses[0]++;
            if (pauses[0] == 5) {
                clock.advance(1);
            }
        };
        SnowflakeIdGenerator g = gen(clock, lazy, 0);
        for (int i = 0; i < 4096; i++) {
            g.nextId();
        }
        long next = g.nextId();
        assertEquals(5, pauses[0]);
        assertEquals(1_001, SnowflakeLayout.timestamp(next));
    }

    /** Tolerance boundary: a step back of exactly the tolerance is absorbed; one millisecond more throws. */
    @Test
    void stepBackBoundaryIsExact() {
        FakeClock clock = new FakeClock(START);
        SnowflakeIdGenerator g = gen(clock, Waiter.spin(), 5);
        long first = g.nextId();
        clock.set(START - 5);
        long second = g.nextId();
        assertEquals(SnowflakeLayout.timestamp(first), SnowflakeLayout.timestamp(second));
        assertEquals(1, SnowflakeLayout.sequence(second));
        assertTrue(second > first);
        clock.set(START - 6);
        ClockMovedBackwardsException e = assertThrows(ClockMovedBackwardsException.class, g::nextId);
        assertEquals(6, e.driftMillis());
    }

    /** With tolerance 0 even a 1 ms step back throws, and once the clock recovers the generator carries on without repeating an ID. */
    @Test
    void zeroToleranceThrowsThenRecovers() {
        FakeClock clock = new FakeClock(START);
        SnowflakeIdGenerator g = gen(clock, Waiter.spin(), 0);
        long first = g.nextId();
        clock.advance(-1);
        assertThrows(ClockMovedBackwardsException.class, g::nextId);
        clock.advance(2);
        assertTrue(g.nextId() > first);
    }

    /** While the clock is stepped back, IDs keep rising (same millisecond, higher sequence) and never repeat. */
    @Test
    void idsStayStrictlyIncreasingDuringStepBack() {
        FakeClock clock = new FakeClock(START);
        SnowflakeIdGenerator g = gen(clock, Waiter.spin(), 10);
        long prev = g.nextId();
        clock.set(START - 3);
        for (int i = 0; i < 50; i++) {
            long id = g.nextId();
            assertTrue(id > prev);
            prev = id;
        }
    }

    /**
     * Step-back and overflow together: the clock is 3 ms behind (inside the tolerance of 5) and the sequence runs out. The generator must wait for the real clock to pass the last timestamp and must not jump to the next millisecond on its own.
     */
    @Test
    void absorbedStepBackThenOverflowWaitsForRealClock() {
        FakeClock clock = new FakeClock(START);
        FakeClock.CountingWaiter waiter = clock.advancingWaiter();
        SnowflakeIdGenerator g = gen(clock, waiter, 5);
        g.nextId(); // (1000, 0)
        clock.set(START - 3); // 3 ms behind
        for (int i = 1; i < 4096; i++) {
            g.nextId(); // (1000, 1..4095), all absorbed
        }
        assertEquals(0, waiter.calls());
        long next = g.nextId();
        assertEquals(4, waiter.calls()); // clock went 997 -> 1001, one pause per ms
        assertEquals(1_001, SnowflakeLayout.timestamp(next));
        assertEquals(0, SnowflakeLayout.sequence(next));
        assertTrue(SnowflakeLayout.timestamp(next) <= clock.millis() - EPOCH, "ID is from the future");
    }

    /** A clock earlier than the epoch is a misconfiguration and is reported, not turned into a negative timestamp. */
    @Test
    void clockBeforeEpochRejected() {
        SnowflakeIdGenerator g = gen(new FakeClock(EPOCH - 1), Waiter.spin(), 0);
        IllegalStateException e = assertThrows(IllegalStateException.class, g::nextId);
        assertEquals(IllegalStateException.class, e.getClass());
        assertTrue(e.getMessage().contains("before the epoch"));
    }

    /** When the clock is 2^41 ms past the epoch the timestamp bits are used up, and the generator refuses instead of wrapping. */
    @Test
    void timestampExhaustionRejected() {
        FakeClock lastGood = new FakeClock(EPOCH + SnowflakeLayout.MAX_TIMESTAMP);
        long id = gen(lastGood, Waiter.spin(), 0).nextId();
        assertEquals(SnowflakeLayout.MAX_TIMESTAMP, SnowflakeLayout.timestamp(id));
        FakeClock tooLate = new FakeClock(EPOCH + SnowflakeLayout.MAX_TIMESTAMP + 1);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> gen(tooLate, Waiter.spin(), 0).nextId());
        assertEquals(IllegalStateException.class, e.getClass());
        assertTrue(e.getMessage().contains("exhausted"));
    }

    /** A bad node number is rejected at construction. */
    @Test
    void invalidNodeRejected() {
        FakeClock c = new FakeClock(START);
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(c, Waiter.spin(), 1024, EPOCH, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(c, Waiter.spin(), -1, EPOCH, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(c, Waiter.spin(), 0, EPOCH, -1));
    }

    /**
     * Forces the check-then-act window: two threads both read the same state and both compute the same next ID, then a barrier releases them into the CAS together. Exactly one wins; the loser must retry and take a different ID.
     */
    @Test
    @Timeout(10)
    void forcedRaceOnStateProducesDistinctIds() throws Exception {
        FakeClock clock = new FakeClock(START);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ThreadLocal<Boolean> first = ThreadLocal.withInitial(() -> true);
        Runnable hook = () -> {
            if (first.get()) {
                first.set(false);
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(clock, Waiter.spin(), 7, EPOCH, 0, hook);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> a = pool.submit(g::nextId);
            Future<Long> b = pool.submit(g::nextId);
            long x = a.get(5, TimeUnit.SECONDS);
            long y = b.get(5, TimeUnit.SECONDS);
            assertTrue(x != y, "both threads got the same ID");
            assertEquals(Set.of(0, 1), Set.of(SnowflakeLayout.sequence(x), SnowflakeLayout.sequence(y)));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 16 threads released together by a start gate each take 20,000 IDs on a fake clock whose waiter advances time: exactly 320,000 distinct IDs, and each thread sees its own IDs rising. */
    @Test
    @Timeout(30)
    void manyThreadsFakeClockExactTotals() throws Exception {
        FakeClock clock = new FakeClock(START);
        runContended(gen(clock, clock.advancingWaiter(), 0), 16, 20_000);
    }

    /** Runs {@code threads} workers behind a start gate, then checks per-thread ordering and that the total number of distinct IDs is exactly threads x perThread. */
    private static void runContended(IdGenerator g, int threads, int perThread) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    long[] ids = new long[perThread];
                    for (int i = 0; i < perThread; i++) {
                        ids[i] = g.nextId();
                    }
                    return ids;
                }));
            }
            gate.countDown();
            Set<Long> all = new HashSet<>();
            for (Future<long[]> f : futures) {
                long[] ids = f.get(25, TimeUnit.SECONDS);
                for (int i = 1; i < ids.length; i++) {
                    assertTrue(ids[i] > ids[i - 1], "a thread saw a non-increasing ID");
                }
                for (long id : ids) {
                    all.add(id);
                }
            }
            assertEquals(threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
