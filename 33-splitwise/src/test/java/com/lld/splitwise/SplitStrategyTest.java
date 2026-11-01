package com.lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Proves the four split strategies: exact sums, the tie-break, the rejections, and a seeded property test. */
class SplitStrategyTest {

    private static final Map<String, Long> NONE = Map.of();

    /** Builds a map from user ids to the same value, keeping the order the ids are given in (a LinkedHashMap, so tests control the insertion order). */
    private static Map<String, Long> same(long v, String... users) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String u : users) {
            m.put(u, v);
        }
        return m;
    }

    /** 100 split 3 ways gives 34/33/33 and the extra cent goes to the smallest user id, whatever the insertion order. */
    @Test
    void hundredByThreeGivesExtraCentToSmallestId() {
        Map<String, Long> r = Splits.equal().split(100, same(0, "carol", "alice", "bob"));
        assertEquals(34L, r.get("alice"));
        assertEquals(33L, r.get("bob"));
        assertEquals(33L, r.get("carol"));
    }

    /** Shares 1:2 of 11: the exact values are 3.67 and 7.33, so the left-over cent goes to the larger remainder (the weight 1 person), giving 4 and 7. */
    @Test
    void sharesOneToTwoOfElevenGivesTheCentToTheLargerRemainder() {
        assertEquals(Map.of("a", 4L, "b", 7L), Splits.shares().split(11, Map.of("a", 1L, "b", 2L)));
    }

    /** 101 split 3 ways gives two extra cents, to the two smallest ids. */
    @Test
    void twoExtraCentsGoToTwoSmallestIds() {
        Map<String, Long> r = Splits.equal().split(101, same(0, "a", "b", "c"));
        assertEquals(Map.of("a", 34L, "b", 34L, "c", 33L), r);
    }

    /** Exact amounts that add up are returned unchanged. */
    @Test
    void exactAcceptsMatchingAmounts() {
        assertEquals(Map.of("a", 70L, "b", 30L), Splits.exact().split(100, Map.of("a", 70L, "b", 30L)));
    }

    /** Exact amounts that are one cent short or one cent over are rejected. */
    @Test
    void exactRejectsWrongSum() {
        assertThrows(IllegalArgumentException.class, () -> Splits.exact().split(100, Map.of("a", 70L, "b", 29L)));
        assertThrows(IllegalArgumentException.class, () -> Splits.exact().split(100, Map.of("a", 70L, "b", 31L)));
    }

    /** Percent 33.33 / 33.33 / 33.34 of 100 uses basis points and still sums to 100. */
    @Test
    void percentUsesBasisPointsAndLargestRemainder() {
        Map<String, Long> r = Splits.percent().split(100, Map.of("a", 3333L, "b", 3333L, "c", 3334L));
        assertEquals(100L, r.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of("a", 33L, "b", 33L, "c", 34L), r);
    }

    /** Percents that do not add up to exactly 10000 basis points are rejected. */
    @Test
    void percentRejectsNotHundred() {
        assertThrows(IllegalArgumentException.class, () -> Splits.percent().split(100, Map.of("a", 5000L, "b", 4999L)));
    }

    /** Shares are weighted: 10 split 1:2 gives 3 and 7 (the 1/3 remainder 10/3 loses to 20/3). */
    @Test
    void sharesAreWeighted() {
        assertEquals(Map.of("a", 3L, "b", 7L), Splits.shares().split(10, Map.of("a", 1L, "b", 2L)));
    }

    /** Every bad input is an IllegalArgumentException: zero or negative total, empty input, negative value, all-zero weights. */
    @Test
    void badInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Splits.equal().split(0, same(0, "a")));
        assertThrows(IllegalArgumentException.class, () -> Splits.equal().split(-5, same(0, "a")));
        assertThrows(IllegalArgumentException.class, () -> Splits.equal().split(5, NONE));
        assertThrows(IllegalArgumentException.class, () -> Splits.equal().split(5, null));
        assertThrows(IllegalArgumentException.class, () -> Splits.exact().split(5, Map.of("a", -1L, "b", 6L)));
        assertThrows(IllegalArgumentException.class, () -> Splits.shares().split(5, same(0, "a", "b")));
    }

    /** A huge total times a huge weight overflows, and that is reported instead of wrapping around. */
    @Test
    void overflowIsReportedNotWrapped() {
        assertThrows(ArithmeticException.class, () -> Splits.shares().split(Long.MAX_VALUE, Map.of("a", 5L, "b", 5L)));
    }

    /** Seeded property test: for every strategy and 2000 random inputs the shares sum exactly to the total and none is negative. */
    @Test
    void sharesAlwaysSumExactlyAndNeverNegative() {
        Random rnd = new Random(33);
        for (int round = 0; round < 2000; round++) {
            int n = 1 + rnd.nextInt(8);
            long total = 1 + rnd.nextInt(1_000_000);
            Map<String, Long> weights = new TreeMap<>();
            Map<String, Long> exact = new TreeMap<>();
            Map<String, Long> bps = new TreeMap<>();
            long leftExact = total;
            long leftBps = Splits.FULL_BASIS_POINTS;
            for (int i = 0; i < n; i++) {
                String u = "u" + i;
                weights.put(u, 1L + rnd.nextInt(50));
                long e = i == n - 1 ? leftExact : (long) (rnd.nextDouble() * leftExact);
                long b = i == n - 1 ? leftBps : (long) (rnd.nextDouble() * leftBps);
                exact.put(u, e);
                bps.put(u, b);
                leftExact -= e;
                leftBps -= b;
            }
            check(total, Splits.equal().split(total, weights));
            Map<String, Long> byWeight = Splits.shares().split(total, weights);
            check(total, byWeight);
            checkLargestRemainder(total, weights, byWeight);
            checkLargestRemainder(total, bps, Splits.percent().split(total, bps));
            check(total, Splits.exact().split(total, exact));
            check(total, Splits.percent().split(total, bps));
        }
    }

    /**
     * Asserts the largest-remainder rule itself: every share is the rounded-down exact value or one more, and anyone who got the extra unit has a bigger remainder than anyone who did not (a tie goes to the smaller id).
     */
    private static void checkLargestRemainder(long total, Map<String, Long> weights, Map<String, Long> shares) {
        long weightSum = weights.values().stream().mapToLong(Long::longValue).sum();
        for (String r : weights.keySet()) {
            long floor = total * weights.get(r) / weightSum;
            long extra = shares.get(r) - floor;
            assertTrue(extra == 0 || extra == 1, "share is not within 1 of exact for " + r);
        }
        for (String r : weights.keySet()) {
            for (String n : weights.keySet()) {
                boolean rGotExtra = shares.get(r) > total * weights.get(r) / weightSum;
                boolean nGotExtra = shares.get(n) > total * weights.get(n) / weightSum;
                if (rGotExtra && !nGotExtra) {
                    long remR = total * weights.get(r) % weightSum;
                    long remN = total * weights.get(n) % weightSum;
                    assertTrue(remR > remN || (remR == remN && r.compareTo(n) < 0), r + " got the extra unit over " + n);
                }
            }
        }
    }

    /** Asserts the two success rules: exact sum and no negative share. */
    private static void check(long total, Map<String, Long> shares) {
        long sum = 0;
        for (long s : shares.values()) {
            assertTrue(s >= 0, "negative share " + s);
            sum += s;
        }
        assertEquals(total, sum);
    }
}
