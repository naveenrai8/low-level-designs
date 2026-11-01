package com.lld.logging;

/**
 * A destination for log lines: console, file, memory, a queue in front of another appender.
 *
 * <p>Error contract: an appender may throw a {@link RuntimeException} when it cannot write. The {@link LoggerContext} catches it, counts it and carries on with the other appenders, so a broken appender never reaches the caller's code. An appender must be safe to call from many threads.
 */
public interface Appender {

    /** Writes one event. May throw if the destination is broken; the caller of the logger never sees that. */
    void append(LogEvent event);

    /** Releases resources and flushes anything still buffered. Must be safe to call more than once. The default does nothing. */
    default void close() {
    }
}
