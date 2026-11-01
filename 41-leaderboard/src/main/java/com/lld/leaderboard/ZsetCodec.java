package com.lld.leaderboard;

/**
 * Packs a score and a tie-breaking time into the single double score that a Redis sorted set (ZSET) stores.
 *
 * <p>A ZSET sorts by one double, then by member name. A double holds integers exactly only up to 2^53. We split those 53 bits: the low 24 bits hold "seconds since the season started, flipped", the high 29 bits hold the score. Flipping the time (max minus seconds) means that for equal scores the EARLIER time gives the BIGGER number, so it ranks higher in ZREVRANGE, matching our tie rule.
 *
 * <p>The board accepts negative scores but this codec rejects them (a ZSET would need an offset). Redis breaks equal-score ties by member name, and a descending read reverses that, see {@link #encodeAscending}.
 *
 * <p>Limits: score from 0 to 2^29 - 1 (about 536 million), time from 0 to 2^24 - 1 seconds (about 194 days). Outside that, {@link #encode} throws instead of silently losing precision.
 */
public final class ZsetCodec {

    /** Number of low bits holding the flipped time. */
    static final int TIME_BITS = 24;
    /** Largest time offset in seconds. */
    static final long MAX_SECONDS = (1L << TIME_BITS) - 1;
    /** Largest score that still fits in 53 bits next to the time. */
    static final long MAX_SCORE = (1L << (53 - TIME_BITS)) - 1;

    /** Not instantiable, this is a set of static helpers. */
    private ZsetCodec() {
    }

    /** Returns the ZSET score for a leaderboard score reached {@code secondsIntoSeason} seconds after the season started. Throws IllegalArgumentException when either value is out of range. */
    public static double encode(long score, long secondsIntoSeason) {
        if (score < 0 || score > MAX_SCORE) {
            throw new IllegalArgumentException("score out of range 0.." + MAX_SCORE + ": " + score);
        }
        if (secondsIntoSeason < 0 || secondsIntoSeason > MAX_SECONDS) {
            throw new IllegalArgumentException("seconds out of range 0.." + MAX_SECONDS + ": " + secondsIntoSeason);
        }
        return (double) ((score << TIME_BITS) | (MAX_SECONDS - secondsIntoSeason));
    }

    /** Returns the NEGATED packed score. In an ascending ZSET (ZRANGE, ZRANK) the biggest packed value then sorts first, and members with an equal score sort by name, smallest first. That is exactly our tie rule, which a descending ZREV* read cannot give (see the README). */
    public static double encodeAscending(long score, long secondsIntoSeason) {
        return -encode(score, secondsIntoSeason);
    }

    /** Recovers the leaderboard score from a ZSET score made by {@link #encode}. */
    public static long decodeScore(double zsetScore) {
        return ((long) zsetScore) >>> TIME_BITS;
    }

    /** Recovers the seconds-into-season from a ZSET score made by {@link #encode}. */
    public static long decodeSeconds(double zsetScore) {
        return MAX_SECONDS - (((long) zsetScore) & MAX_SECONDS);
    }
}
