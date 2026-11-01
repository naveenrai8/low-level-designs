package com.lld.time;

/**
 * A monotonic nanosecond source: the right input for elapsed time, timeouts and rates.
 * <p>{@link System#nanoTime()} never goes backwards and is not moved by NTP, but its value has no meaning on its own. Only differences between two readings in the same JVM do.
 * <p>Use a {@link java.time.Clock} when you need "what time is it" (expiry stamps shown to users, audit logs), and a {@code Ticker} when you need "how long has it been".
 */
@FunctionalInterface
public interface Ticker {

    /**
     * Returns nanoseconds from an arbitrary origin. Only differences between readings are meaningful.
     */
    long read();

    /**
     * Returns the production ticker, backed by {@code System.nanoTime()}.
     */
    static Ticker system() {
        return System::nanoTime;
    }
}
