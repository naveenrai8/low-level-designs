package com.lld.metrics;

/**
 * The histogram bin layout: small numbers get their own bin, bigger numbers share bins that grow with the number (this is the HdrHistogram idea).
 *
 * <p>Values 0..127 each have an exact bin. From 128 up, every power-of-two range (128..255, 256..511, ...) is cut into 64 equal bins. So a bin is never wider than 1/64 of its smallest value.
 *
 * <p>We report the middle of a bin, so the relative error is at most 1/128 (0.78 percent). Values above {@link #MAX_TRACKABLE} are clamped into the top bin and lose that guarantee. The layout is fixed, so any two histograms can be merged by adding counts.
 */
public final class LogLinear {

    /** Values below this number have an exact bin each. */
    static final int LINEAR = 128;
    /** How many bins each power-of-two range is cut into. */
    static final int PER_OCTAVE = 64;
    /** The biggest value tracked with the error bound (2^40, about 18 minutes if the unit is nanoseconds). */
    public static final long MAX_TRACKABLE = 1L << 40;
    /** The proven worst-case relative error of a reported percentile. */
    public static final double MAX_RELATIVE_ERROR = 1.0 / 128;
    /** How many bins the layout has. */
    public static final int SIZE = rawIndex(MAX_TRACKABLE) + 1;

    /** Not meant to be created; this class only holds static helpers. */
    private LogLinear() {
    }

    /** Returns the bin for {@code value}. Values above {@link #MAX_TRACKABLE} land in the top bin. */
    public static int indexOf(long value) {
        return rawIndex(Math.min(value, MAX_TRACKABLE));
    }

    /** Computes the bin number without clamping; only valid for 0 up to {@link #MAX_TRACKABLE}. */
    private static int rawIndex(long v) {
        if (v < LINEAR) {
            return (int) v;
        }
        int e = 63 - Long.numberOfLeadingZeros(v);
        int shift = e - 6;
        return LINEAR + (e - 7) * PER_OCTAVE + (int) ((v >> shift) - PER_OCTAVE);
    }

    /** Returns the smallest value that falls in bin {@code index}. */
    public static long lowerBound(int index) {
        if (index < LINEAR) {
            return index;
        }
        int k = index - LINEAR;
        int e = 7 + k / PER_OCTAVE;
        int sub = k % PER_OCTAVE;
        return (long) (PER_OCTAVE + sub) << (e - 6);
    }

    /** Returns how many different values share bin {@code index} (1 for the exact bins). */
    public static long width(int index) {
        if (index < LINEAR) {
            return 1;
        }
        return 1L << (7 + (index - LINEAR) / PER_OCTAVE - 6);
    }

    /** Returns the value we report for bin {@code index}: its middle. Exact for the first 128 bins. */
    public static long representative(int index) {
        return lowerBound(index) + width(index) / 2;
    }

    /**
     * Returns the 1-based position of the sample that answers quantile {@code q} among {@code total} sorted samples (the "nearest rank" rule).
     *
     * <p>It is at least 1. The tests use this same function for the exact answer, so the measured error is only the bin rounding, not a different rank rule.
     *
     * <p>Floating point can turn an exact whole number into 7.000000000000001 (for example 0.28 times 25), and a plain ceiling would then pick rank 8. A product within a tiny tolerance of a whole number is treated as that whole number.
     */
    public static long rank(double q, long total) {
        double x = q * total;
        long nearest = Math.round(x);
        long ceiling = Math.abs(x - nearest) <= 1e-9 * Math.max(1.0, x) ? nearest : (long) Math.ceil(x);
        return Math.max(1, ceiling);
    }

    /** Walks the bin counts until {@link #rank} samples are covered and returns that bin's representative value. {@code total} must be above zero. */
    static long quantile(long[] counts, long total, double q) {
        long target = rank(q, total);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= target) {
                return representative(i);
            }
        }
        return representative(counts.length - 1);
    }
}
