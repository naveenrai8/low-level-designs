package com.lld.idgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves UUIDv7 against RFC 9562: layout, version and variant, timestamp, and sort order. */
class UuidV7GeneratorTest {

    private static final long START = 1_700_000_000_000L;

    /** Builds a generator with a seeded Random so the output is exact. */
    private static UuidV7Generator gen(FakeClock clock, Waiter w) {
        return new UuidV7Generator(clock, w, new Random(1234), 100);
    }

    /** The RFC 9562 appendix A.6 example vector is reproduced bit for bit. */
    @Test
    void matchesRfcExampleVector() {
        UUID u = UuidV7Generator.toUuid(0x017F22E279B0L, 0xCC3, 0x18C4DC0C0C07398FL);
        assertEquals("017f22e2-79b0-7cc3-98c4-dc0c0c07398f", u.toString());
    }

    /** Every UUID has version nibble 7 and variant bits 10, checked through both the string and java.util.UUID. */
    @Test
    void versionAndVariantAreCorrect() {
        FakeClock clock = new FakeClock(START);
        UuidV7Generator g = gen(clock, clock.advancingWaiter());
        for (int i = 0; i < 10_000; i++) {
            UUID u = g.nextUuid();
            assertEquals(7, u.version());
            assertEquals(2, u.variant());
            assertEquals('7', u.toString().charAt(14));
            assertTrue("89ab".indexOf(u.toString().charAt(19)) >= 0);
        }
    }

    /** The first 48 bits are the Unix millisecond time, and the string starts with its hex form. */
    @Test
    void timestampIs48BitUnixMillis() {
        FakeClock clock = new FakeClock(START);
        UUID u = gen(clock, Waiter.spin()).nextUuid();
        assertEquals(START, UuidV7Generator.unixMillis(u));
        String hex = String.format("%012x", START);
        assertEquals(hex.substring(0, 8) + "-" + hex.substring(8), u.toString().substring(0, 13));
    }

    /** 20,000 UUIDs from one frozen start (so the 12-bit counter overflows several times and the waiter runs) stay in strictly increasing text order. */
    @Test
    void textOrderEqualsGenerationOrderAcrossCounterOverflow() {
        FakeClock clock = new FakeClock(START);
        FakeClock.CountingWaiter w = clock.advancingWaiter();
        UuidV7Generator g = gen(clock, w);
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            made.add(g.next());
        }
        for (int i = 1; i < made.size(); i++) {
            assertTrue(made.get(i).compareTo(made.get(i - 1)) > 0, "out of order at " + i);
        }
        assertEquals(made.size(), new HashSet<>(made).size());
        assertTrue(w.calls() >= 4, "the counter should have overflowed and waited");
    }

    /** A new millisecond starts the counter in the lower half (top bit 0), so a full 2048 more IDs always fit before the next wait. */
    @Test
    void counterStartsInLowerHalfWithRoomToCount() {
        FakeClock clock = new FakeClock(START);
        UuidV7Generator g = gen(clock, Waiter.spin());
        for (int ms = 0; ms < 200; ms++) {
            clock.advance(1);
            int counter = (int) (g.nextUuid().getMostSignificantBits() & 0xFFF);
            assertTrue(counter < 0x800);
        }
    }

    /** If the clock steps back, UUIDs keep the last millisecond and keep rising instead of going backwards. */
    @Test
    void clockStepBackKeepsOrder() {
        FakeClock clock = new FakeClock(START);
        UuidV7Generator g = gen(clock, Waiter.spin());
        String a = g.next();
        clock.advance(-50);
        String b = g.next();
        assertTrue(b.compareTo(a) > 0);
        assertEquals(START, UuidV7Generator.unixMillis(UUID.fromString(b)));
    }

    /** Inside one millisecond the 12-bit counter goes up by exactly 1 each time, not just "somewhere higher". */
    @Test
    void sameMillisecondCounterStepsByExactlyOne() {
        FakeClock clock = new FakeClock(START);
        UuidV7Generator g = gen(clock, Waiter.spin());
        int prev = (int) (g.nextUuid().getMostSignificantBits() & 0xFFF);
        for (int i = 0; i < 500; i++) {
            int cur = (int) (g.nextUuid().getMostSignificantBits() & 0xFFF);
            assertEquals(prev + 1, cur);
            prev = cur;
        }
    }

    /** Tolerance boundary: 100 ms behind is absorbed, 101 ms behind throws, and the exception reports the drift. */
    @Test
    void stepBackBoundaryIsExact() {
        FakeClock clock = new FakeClock(START);
        UuidV7Generator g = gen(clock, Waiter.spin());
        String first = g.next();
        clock.set(START - 100);
        assertTrue(g.next().compareTo(first) > 0);
        clock.set(START - 101);
        ClockMovedBackwardsException e = assertThrows(ClockMovedBackwardsException.class, g::next);
        assertEquals(101, e.driftMillis());
    }

    /** A clock outside the 48-bit range is refused instead of silently truncated. */
    @Test
    void clockOutsideFortyEightBitsRejected() {
        assertThrows(IllegalStateException.class, () -> gen(new FakeClock(-1), Waiter.spin()).next());
        assertThrows(IllegalStateException.class, () -> gen(new FakeClock(1L << 48), Waiter.spin()).next());
    }

    /**
     * Forced interleaving: thread A is frozen inside the generator (in its random draw), then thread B starts. B must block on the lock. Without the lock B would run straight through and could take a counter that A later overwrites.
     */
    @Test
    @Timeout(20)
    void secondThreadBlocksWhileFirstIsInsideGenerator() throws Exception {
        FakeClock clock = new FakeClock(START);
        GatedRandom gate = new GatedRandom();
        UuidV7Generator g = new UuidV7Generator(clock, Waiter.spin(), gate, 0);
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        String state = GatedRandom.raceTwoThreads(gate, () -> out.add(g.next()));
        assertEquals("BLOCKED", state);
        assertEquals(2, out.size());
        assertEquals(2, new HashSet<>(out).size());
        assertTrue(out.get(1).compareTo(out.get(0)) > 0);
    }

    /** Eight threads released by a start gate share one generator and produce exactly 8 x 5,000 distinct UUIDs; worker failures are rethrown. */
    @Test
    @Timeout(30)
    void manyThreadsExactTotals() throws Exception {
        FakeClock clock = new FakeClock(START);
        UuidV7Generator g = gen(clock, clock.advancingWaiter());
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    List<String> mine = new ArrayList<>();
                    for (int i = 0; i < 5_000; i++) {
                        mine.add(g.next());
                    }
                    return mine;
                }));
            }
            gate.countDown();
            Set<String> all = new HashSet<>();
            for (Future<List<String>> f : futures) {
                List<String> mine = f.get(25, TimeUnit.SECONDS);
                for (int i = 1; i < mine.size(); i++) {
                    assertTrue(mine.get(i).compareTo(mine.get(i - 1)) > 0);
                }
                all.addAll(mine);
            }
            assertEquals(40_000, all.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
