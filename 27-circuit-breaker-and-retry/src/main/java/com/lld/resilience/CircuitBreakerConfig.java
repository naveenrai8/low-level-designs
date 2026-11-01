package com.lld.resilience;

import java.time.Duration;

/**
 * The settings of one breaker.
 *
 * @param failureRatePercent trip when failures are at least this percent of the calls in the window (1 to 100)
 * @param minimumCalls do not judge the rate until the window holds at least this many calls
 * @param windowCalls size of a count-based window; 0 means the window is time-based
 * @param windowNanos length of a time-based window in nanoseconds; 0 means the window is count-based
 * @param waitInOpenNanos how long to stay OPEN before moving to HALF_OPEN
 * @param permittedHalfOpenCalls how many probe calls HALF_OPEN lets in, and how many must succeed to close
 * @param maxWaitInHalfOpenNanos how long HALF_OPEN may last; if the probes have not all reported by then (a hung or cancelled probe) the breaker goes back to OPEN instead of waiting forever
 */
public record CircuitBreakerConfig(int failureRatePercent, int minimumCalls, int windowCalls, long windowNanos, long waitInOpenNanos, int permittedHalfOpenCalls, long maxWaitInHalfOpenNanos) {

    /** How long HALF_OPEN may last when the config does not say: 60 seconds. */
    public static final Duration DEFAULT_MAX_WAIT_IN_HALF_OPEN = Duration.ofSeconds(60);

    /** Checks every setting and that exactly one window kind is chosen. */
    public CircuitBreakerConfig {
        if (failureRatePercent < 1 || failureRatePercent > 100) {
            throw new IllegalArgumentException("failureRatePercent must be 1..100");
        }
        if (minimumCalls < 1 || waitInOpenNanos < 1 || permittedHalfOpenCalls < 1 || maxWaitInHalfOpenNanos < 1) {
            throw new IllegalArgumentException("minimumCalls, waitInOpen, permittedHalfOpenCalls and maxWaitInHalfOpen must be positive");
        }
        if ((windowCalls > 0) == (windowNanos > 0)) {
            throw new IllegalArgumentException("choose exactly one of a count window or a time window");
        }
        if (windowCalls > 0 && minimumCalls > windowCalls) {
            throw new IllegalArgumentException("minimumCalls can never be reached: it is bigger than the window");
        }
    }

    /** Builds a config with a count-based window of the last {@code windowCalls} calls. */
    public static CircuitBreakerConfig countBased(int failureRatePercent, int minimumCalls, int windowCalls, Duration waitInOpen, int permittedHalfOpenCalls) {
        return new CircuitBreakerConfig(failureRatePercent, minimumCalls, windowCalls, 0, waitInOpen.toNanos(), permittedHalfOpenCalls, DEFAULT_MAX_WAIT_IN_HALF_OPEN.toNanos());
    }

    /** Builds a config with a time-based window covering the last {@code window} of time. */
    public static CircuitBreakerConfig timeBased(int failureRatePercent, int minimumCalls, Duration window, Duration waitInOpen, int permittedHalfOpenCalls) {
        return new CircuitBreakerConfig(failureRatePercent, minimumCalls, 0, window.toNanos(), waitInOpen.toNanos(), permittedHalfOpenCalls, DEFAULT_MAX_WAIT_IN_HALF_OPEN.toNanos());
    }

    /** Returns a copy of this config with a different limit on how long HALF_OPEN may wait for its probes to report. */
    public CircuitBreakerConfig withMaxWaitInHalfOpen(Duration maxWait) {
        return new CircuitBreakerConfig(failureRatePercent, minimumCalls, windowCalls, windowNanos, waitInOpenNanos, permittedHalfOpenCalls, maxWait.toNanos());
    }

    /** Creates a fresh, empty window of the kind this config asks for. */
    SlidingWindow newWindow() {
        return windowCalls > 0 ? new CountWindow(windowCalls) : new TimeWindow(windowNanos);
    }
}
