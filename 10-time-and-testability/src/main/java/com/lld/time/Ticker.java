package com.lld.time;

/**
 * A monotonic nanosecond source: the right input for <em>elapsed</em> time, timeouts and rates.
 *
 * <p>{@link System#nanoTime()} never goes backwards and isn't moved by NTP, but its value has no
 * meaning on its own. Only differences between two readings in the same JVM do. Use a
 * {@link java.time.Clock} when you need "what time is it" (expiry stamps shown to users, audit
 * logs), and a {@code Ticker} when you need "how long has it been".
 */
@FunctionalInterface
public interface Ticker {

    /** Nanoseconds from an arbitrary origin. Only differences between readings are meaningful. */
    long read();

    static Ticker system() {
        return System::nanoTime;
    }
}
