package com.lld.metrics;

import java.util.OptionalLong;

/**
 * Counts and latency percentiles over the last N seconds.
 *
 * <p>Values are non-negative {@code long}s in one unit the caller picks (for example microseconds). Time is cut into B time slices of N/B seconds. The window is the (B-1) newest full slices plus the current partial slice, so a sample is forgotten between (B-1)/B and B/B of N after it was recorded: possibly up to one slice early, never late.
 *
 * <p>Error contract:
 * <ul>
 * <li>{@link #record} throws {@link IllegalArgumentException} for a negative value. Values above {@link LogLinear#MAX_TRACKABLE} are accepted but lose the percentile error bound.</li>
 * <li>{@link #percentile} throws {@link IllegalArgumentException} when {@code q} is NaN or outside 0..1. On an empty window it returns an empty {@link OptionalLong}, never a made-up 0.</li>
 * <li>{@link #count}, {@link #sum} and {@link #rate} return 0 on an empty window.</li>
 * </ul>
 */
public interface WindowedMetric {

    /** Adds one sample at the current time. */
    void record(long value);

    /** Returns how many samples are inside the window. */
    long count();

    /** Returns the sum of the samples inside the window (it stops at Long.MAX_VALUE instead of wrapping). */
    long sum();

    /**
     * Returns samples per second, measured over the (B-1) newest full time slices only, so it does not saw-tooth when a new slice starts.
     *
     * <p>The price is that it lags by the current partial slice. {@link #count()} includes that partial slice, so {@code rate() * window} can differ from {@code count()}.
     */
    double rate();

    /** Returns the value at quantile {@code q} (0.99 means p99) with at most {@link LogLinear#MAX_RELATIVE_ERROR} relative error, or empty if the window has no samples. */
    OptionalLong percentile(double q);

    /** Returns an immutable copy of the window, taken slice by slice (each slice is consistent, the whole is not a single atomic instant). */
    MetricSnapshot snapshot();
}
