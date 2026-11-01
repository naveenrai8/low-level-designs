package com.lld.idgen;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * ULID generator: 48-bit unix-ms time followed by 80 random bits, written as 26 Crockford base32 characters.
 *
 * <p>Monotonic rule (from the ULID spec): in the same millisecond the previous 80-bit random part is incremented by 1 instead of drawing a new one. If it would overflow 80 bits, the spec says to error; here we wait for the next millisecond through {@link Waiter}. A clock that steps back by at most {@code maxBackwardsMillis} keeps the last millisecond; a bigger step throws {@link ClockMovedBackwardsException}. That bound is also the worst-case stall: a step back of d ms plus an exhausted random part waits at most d + 1 ms, never an hour.
 *
 * <p>Thread safety: one lock guards the (lastMillis, random) state.
 */
public final class UlidGenerator implements StringIdGenerator {

    /** Crockford base32: digits and letters without I, L, O, U. Characters are in ascending ASCII order, so text order equals numeric order. */
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final Clock clock;
    private final Waiter waiter;
    private final RandomGenerator random;
    private final long maxBackwardsMillis;
    private long lastMillis = -1;
    /** Top 16 bits of the 80-bit random part. */
    private long randHi;
    /** Low 64 bits of the 80-bit random part. */
    private long randLo;

    /** Creates a generator. Pass a seeded {@code Random} in tests for exact output. {@code maxBackwardsMillis} is how far the clock may step back before {@link #next()} throws. */
    public UlidGenerator(Clock clock, Waiter waiter, RandomGenerator random, long maxBackwardsMillis) {
        if (maxBackwardsMillis < 0) {
            throw new IllegalArgumentException("maxBackwardsMillis must be >= 0");
        }
        this.maxBackwardsMillis = maxBackwardsMillis;
        this.clock = Objects.requireNonNull(clock);
        this.waiter = Objects.requireNonNull(waiter);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Returns the next ULID, 26 uppercase characters.
     *
     * @throws ClockMovedBackwardsException if the clock is further behind the last issued millisecond than the tolerance
     * @throws IllegalStateException if the clock is outside the 48-bit range
     */
    @Override
    public synchronized String next() {
        while (true) {
            long now = clock.millis();
            if (now < 0 || now >= (1L << 48)) {
                throw new IllegalStateException("clock outside the 48-bit unix-ms range: " + now);
            }
            if (lastMillis - now > maxBackwardsMillis) {
                throw new ClockMovedBackwardsException(lastMillis - now, maxBackwardsMillis);
            }
            if (now > lastMillis) {
                lastMillis = now;
                randHi = random.nextLong() & 0xFFFFL;
                randLo = random.nextLong();
                break;
            }
            if (randLo != -1L) { // low 64 bits not all ones: plain +1
                randLo++;
                break;
            }
            if (randHi != 0xFFFFL) { // carry into the high 16 bits
                randLo = 0;
                randHi++;
                break;
            }
            waiter.pause(); // all 80 bits are ones: wait for the next millisecond
        }
        return encode(lastMillis, randHi, randLo);
    }

    /** Writes a 48-bit time plus 80-bit random as 26 base32 characters (the first carries only 3 bits). */
    static String encode(long millis, long hi16, long lo64) {
        long hi = (millis << 16) | hi16; // the top 64 bits of the 128-bit value
        char[] out = new char[26];
        for (int i = 0; i < 26; i++) {
            int shift = 5 * (25 - i);
            long bits;
            if (shift >= 64) {
                bits = hi >>> (shift - 64);
            } else {
                bits = lo64 >>> shift;
                if (shift > 59) {
                    bits |= hi << (64 - shift);
                }
            }
            out[i] = ALPHABET.charAt((int) (bits & 31));
        }
        return new String(out);
    }

    /**
     * Reads the unix-millisecond timestamp back out of the first 10 characters of a ULID. Lower case is accepted (Crockford base32 is case-insensitive).
     *
     * @throws IllegalArgumentException if the text is too short, has a character outside the alphabet, or starts above '7' (that would not fit in 48 bits)
     */
    public static long unixMillis(String ulid) {
        if (ulid.length() < 10) {
            throw new IllegalArgumentException("a ULID has at least 10 time characters");
        }
        String head = ulid.substring(0, 10).toUpperCase(Locale.ROOT);
        if (head.charAt(0) > '7') {
            throw new IllegalArgumentException("first character above '7' overflows 48 bits: " + head.charAt(0));
        }
        long v = 0;
        for (int i = 0; i < 10; i++) {
            int digit = ALPHABET.indexOf(head.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a Crockford base32 character: " + head.charAt(i));
            }
            v = (v << 5) | digit;
        }
        return v;
    }
}
