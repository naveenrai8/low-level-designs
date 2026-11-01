package com.lld.idgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Proves the ULID generator: encoding against the spec's example, text order, and the monotonic "+1" rule. */
class UlidGeneratorTest {

    private static final long START = 1_700_000_000_000L;

    /** Turns a ULID back into its 128-bit number, so tests can compare values exactly. */
    private static BigInteger decode(String ulid) {
        BigInteger v = BigInteger.ZERO;
        for (char c : ulid.toCharArray()) {
            v = v.shiftLeft(5).add(BigInteger.valueOf(UlidGenerator.ALPHABET.indexOf(c)));
        }
        return v;
    }

    /** The ULID spec's example (time 1469922850259, random part TSV4RRFFQ69G5FAV, which includes a K) is reproduced from hard-coded numbers, not decoded with our own alphabet. */
    @Test
    void encodesSpecExample() {
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", UlidGenerator.encode(1469922850259L, 0xD676L, 0x4C61EFB99302BD5BL));
        assertEquals(1469922850259L, UlidGenerator.unixMillis("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    }

    /** The spec's monotonic pair, exact: from random part ...MMVRZ the next ULID in the same millisecond is ...MMVS0 (the Z carries into the next digit). */
    @Test
    void specMonotonicPairIsExactPlusOneWithCarry() {
        long[] draws = {0x5334L, 0xADA78EDC1D4A6F1FL};
        int[] i = {0};
        RandomGenerator rg = () -> draws[i[0]++];
        UlidGenerator g = new UlidGenerator(new FakeClock(1508808576371L), Waiter.spin(), rg, 0);
        assertEquals("01BX5ZZKBKACTAV9WEVGEMMVRZ", g.next());
        assertEquals("01BX5ZZKBKACTAV9WEVGEMMVS0", g.next());
    }

    /** Decoding accepts lower case, and rejects bad characters, short text and a first character above '7' (that would not fit in 48 bits). */
    @Test
    void decodeIsCaseInsensitiveAndStrict() {
        assertEquals(1469922850259L, UlidGenerator.unixMillis("01arz3ndektsv4rrffq69g5fav"));
        assertThrows(IllegalArgumentException.class, () -> UlidGenerator.unixMillis("81ARZ3NDEK"));
        assertThrows(IllegalArgumentException.class, () -> UlidGenerator.unixMillis("01ARZ3NDEI")); // I is not in the alphabet
        assertThrows(IllegalArgumentException.class, () -> UlidGenerator.unixMillis("01ARZ"));
    }

    /** Output is 26 characters, all from the Crockford alphabet, and the time prefix decodes to the clock. */
    @Test
    void shapeAndTimestamp() {
        UlidGenerator g = new UlidGenerator(new FakeClock(START), Waiter.spin(), new Random(7), 0);
        String u = g.next();
        assertEquals(26, u.length());
        assertTrue(u.chars().allMatch(c -> UlidGenerator.ALPHABET.indexOf(c) >= 0));
        assertEquals(START, UlidGenerator.unixMillis(u));
    }

    /** In one millisecond each ULID is exactly the previous random part plus 1, the spec's monotonic rule. */
    @Test
    void sameMillisecondIncrementsRandomPart() {
        UlidGenerator g = new UlidGenerator(new FakeClock(START), Waiter.spin(), new Random(7), 0);
        BigInteger prev = decode(g.next());
        for (int i = 0; i < 1000; i++) {
            BigInteger cur = decode(g.next());
            assertEquals(prev.add(BigInteger.ONE), cur);
            prev = cur;
        }
    }

    /** A carry from the low 64 bits into the high 16 bits is handled: all-ones low part rolls to zero and the high part goes up by one. */
    @Test
    void carryCrossesTheSixtyFourBitBoundary() {
        long[] draws = {0L, -1L}; // first draw -> hi16 = 0, second draw -> lo64 = all ones
        int[] i = {0};
        RandomGenerator rg = () -> draws[i[0]++ % 2];
        UlidGenerator g = new UlidGenerator(new FakeClock(START), Waiter.spin(), rg, 0);
        BigInteger first = decode(g.next());
        BigInteger second = decode(g.next());
        assertEquals(first.add(BigInteger.ONE), second);
        assertEquals(1, second.shiftRight(64).and(BigInteger.valueOf(0xFFFF)).intValue());
        assertEquals(0, second.longValue());
    }

    /** When all 80 random bits are ones the generator waits for the next millisecond instead of overflowing into the time field. */
    @Test
    void randomPartOverflowWaitsForNextMillisecond() {
        FakeClock clock = new FakeClock(START);
        FakeClock.CountingWaiter w = clock.advancingWaiter();
        RandomGenerator ones = () -> -1L;
        UlidGenerator g = new UlidGenerator(clock, w, ones, 0);
        String a = g.next();
        String b = g.next();
        assertEquals(1, w.calls());
        assertEquals(START, UlidGenerator.unixMillis(a));
        assertEquals(START + 1, UlidGenerator.unixMillis(b));
        assertTrue(b.compareTo(a) > 0);
    }

    /** Across many milliseconds, a step back, and same-ms bursts, text order equals generation order. */
    @Test
    void textOrderEqualsGenerationOrder() {
        FakeClock clock = new FakeClock(START);
        UlidGenerator g = new UlidGenerator(clock, Waiter.spin(), new Random(99), 100);
        Random pace = new Random(5);
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            clock.advance(pace.nextInt(4) - 1); // sometimes steps back 1 ms, sometimes stays, sometimes moves on
            made.add(g.next());
        }
        for (int k = 1; k < made.size(); k++) {
            assertTrue(made.get(k).compareTo(made.get(k - 1)) > 0, "out of order at " + k);
        }
    }

    /** Tolerance boundary: 1 ms behind is absorbed (order kept), 2 ms behind throws. */
    @Test
    void stepBackBoundaryIsExact() {
        FakeClock clock = new FakeClock(START);
        UlidGenerator g = new UlidGenerator(clock, Waiter.spin(), new Random(3), 1);
        String first = g.next();
        clock.set(START - 1);
        assertTrue(g.next().compareTo(first) > 0);
        clock.set(START - 2);
        assertEquals(2, assertThrows(ClockMovedBackwardsException.class, g::next).driftMillis());
    }

    /** A clock outside the 48-bit range is refused. */
    @Test
    void clockOutsideFortyEightBitsRejected() {
        Random r = new Random(1);
        assertThrows(IllegalStateException.class, () -> new UlidGenerator(new FakeClock(-1), Waiter.spin(), r, 0).next());
        assertThrows(IllegalStateException.class, () -> new UlidGenerator(new FakeClock(1L << 48), Waiter.spin(), r, 0).next());
    }

    /** Forced interleaving: thread A is frozen inside the generator, thread B must block on the lock, and the two ULIDs come out distinct and in order. */
    @Test
    @Timeout(20)
    void secondThreadBlocksWhileFirstIsInsideGenerator() throws Exception {
        GatedRandom gate = new GatedRandom();
        UlidGenerator g = new UlidGenerator(new FakeClock(START), Waiter.spin(), gate, 0);
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        String state = GatedRandom.raceTwoThreads(gate, () -> out.add(g.next()));
        assertEquals("BLOCKED", state);
        assertEquals(2, out.size());
        assertTrue(out.get(1).compareTo(out.get(0)) > 0);
    }
}
