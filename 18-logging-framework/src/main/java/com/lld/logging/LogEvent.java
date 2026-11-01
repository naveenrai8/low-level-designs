package com.lld.logging;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One finished log line, frozen at the moment it was logged.
 *
 * <p>It is built on the caller's thread, so the thread name and the MDC context are the caller's, even if an async appender writes it later on another thread. It is immutable, so appenders can share it safely.
 *
 * @param time    when it was logged, from the injected clock
 * @param level   how important it is
 * @param logger  the logger name, such as "shop.orders.pay"
 * @param thread  the name of the thread that logged it
 * @param message the text
 * @param error   the exception to report, or null
 * @param context the MDC key/values active when it was logged (never null)
 * @param fields  structured key/values attached to this one line (never null)
 */
public record LogEvent(Instant time, Level level, String logger, String thread, String message, Throwable error, Map<String, String> context, Map<String, Object> fields) {

    /** Copies both maps into sorted read-only maps, so later changes by the caller cannot alter the event and output order is stable. */
    public LogEvent {
        context = Collections.unmodifiableMap(new TreeMap<>(context));
        fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }
}
