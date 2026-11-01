package com.lld.gateway;

/** The gateway's own elapsed-time source (nanoseconds), so tests can use virtual time. Topics 21 and 27 each define their own {@code Ticker}; this one adapts into both so the whole pipeline moves on ONE clock. */
@FunctionalInterface
public interface Ticker {

    /** Returns a nanosecond reading that only ever goes up. Only differences between two readings mean anything. */
    long read();

    /** Returns the real clock for production use. Tests pass a virtual clock instead. */
    static Ticker system() {
        return System::nanoTime;
    }

    /** Wraps this clock as Topic 21's ticker, so the rate limiter reads the same time as the gateway. */
    default com.lld.ratelimiter.Ticker forRateLimiter() {
        return this::read;
    }

    /** Wraps this clock as Topic 27's ticker, so the circuit breakers read the same time as the gateway. */
    default com.lld.resilience.Ticker forResilience() {
        return this::read;
    }
}
