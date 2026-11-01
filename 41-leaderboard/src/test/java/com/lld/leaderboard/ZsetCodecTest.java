package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Redis ZSET score packing: score in the high bits, flipped time in the low bits. */
class ZsetCodecTest {

    /** Proves encode then decode returns the same score and seconds, including the extremes. */
    @Test
    void roundTrips() {
        double z = ZsetCodec.encode(123_456, 3_600);
        assertEquals(123_456, ZsetCodec.decodeScore(z));
        assertEquals(3_600, ZsetCodec.decodeSeconds(z));
        double top = ZsetCodec.encode(ZsetCodec.MAX_SCORE, 0);
        assertEquals(ZsetCodec.MAX_SCORE, ZsetCodec.decodeScore(top));
        assertEquals(0, ZsetCodec.decodeSeconds(top));
    }

    /** Proves a higher score always beats a lower one, and for equal scores the earlier time gives the larger number (ranks higher in ZREVRANGE). */
    @Test
    void ordersLikeOurTieRule() {
        assertTrue(ZsetCodec.encode(11, ZsetCodec.MAX_SECONDS) > ZsetCodec.encode(10, 0));
        assertTrue(ZsetCodec.encode(10, 5) > ZsetCodec.encode(10, 6));
    }

    /** Proves the packed value is an exact integer in a double: no two different inputs collide at the limits. */
    @Test
    void noPrecisionLossAtLimits() {
        double a = ZsetCodec.encode(ZsetCodec.MAX_SCORE, 0);
        double b = ZsetCodec.encode(ZsetCodec.MAX_SCORE, 1);
        assertTrue(a > b);
        assertEquals(a, (double) (long) a);
    }

    /** Proves out-of-range inputs are rejected instead of silently corrupting the order. */
    @Test
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ZsetCodec.encode(ZsetCodec.MAX_SCORE + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ZsetCodec.encode(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> ZsetCodec.encode(1, -1));
        assertThrows(IllegalArgumentException.class, () -> ZsetCodec.encode(1, ZsetCodec.MAX_SECONDS + 1));
    }

    /** A tiny model of Redis ordering: by score, then by member name (Redis compares members as bytes, plain strings are enough here). */
    private static java.util.List<String> zset(java.util.Map<String, Double> members, boolean reverse) {
        java.util.Comparator<java.util.Map.Entry<String, Double>> asc = java.util.Map.Entry.<String, Double>comparingByValue().thenComparing(java.util.Map.Entry::getKey);
        return members.entrySet().stream().sorted(reverse ? asc.reversed() : asc).map(java.util.Map.Entry::getKey).toList();
    }

    /** Pins the real Redis rule: in a descending read (ZRANGE REV, ZREVRANGE) equal scores come out in REVERSE name order, so bob comes before abe. */
    @Test
    void descendingReadReversesNameOrderForEqualScores() {
        double same = ZsetCodec.encode(10, 5);
        assertEquals(java.util.List.of("bob", "abe"), zset(java.util.Map.of("abe", same, "bob", same), true));
    }

    /** Proves the negated encoding read ascending (ZRANGE, ZRANK) gives our tie rule exactly: higher score, then earlier second, then smaller name. */
    @Test
    void ascendingNegatedEncodingMatchesOurTieRule() {
        java.util.Map<String, Double> m = new java.util.HashMap<>();
        m.put("bob", ZsetCodec.encodeAscending(10, 5));
        m.put("abe", ZsetCodec.encodeAscending(10, 5));
        m.put("cat", ZsetCodec.encodeAscending(10, 9));
        m.put("top", ZsetCodec.encodeAscending(11, 99));
        assertEquals(java.util.List.of("top", "abe", "bob", "cat"), zset(m, false));
    }
}
