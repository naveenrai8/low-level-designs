package com.lld.cache;

/**
 * A monotonic nanosecond time source.
 *
 * <p>TTL is <em>elapsed</em> time, so it uses {@link System#nanoTime()}, not wall-clock time, which NTP can move backwards. Injecting it makes every expiry test deterministic: tests advance a fake ticker instead of sleeping. Same seam as Topic 21's {@code Ticker}, copied rather than shared so the topics build independently.
 */
@FunctionalInterface
public interface Ticker {

    /** Nanoseconds from an arbitrary fixed origin. Only differences between readings are meaningful. */
    long read();

    /** The real clock, backed by {@code System.nanoTime()}. */
    static Ticker system() {
        return System::nanoTime;
    }
}
