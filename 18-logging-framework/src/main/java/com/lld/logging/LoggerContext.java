package com.lld.logging;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hub: creates loggers, holds the current config and fans each event out to every appender.
 *
 * <p>Growth, stated plainly: the map of loggers never shrinks (one small object per distinct name ever asked for), so do not make a logger per request. Failure counters of appenders that a reload removed are forgotten, so a retired appender can be garbage collected.
 *
 * <p>Re-entrancy: if an appender logs through the same context while it is being called, that inner event is dropped and counted in {@link #reentrantDrops()} instead of recursing until a StackOverflowError. The guard is per thread and per context.
 *
 * <p>Isolation contract: an appender that throws is caught, counted ({@link #failureCount()}, {@link #failuresFor(Appender)}, {@link #lastFailure()}) and skipped for that event only. The other appenders still get the event and the caller's code never sees the exception. Errors that are not exceptions (such as OutOfMemoryError) are not caught on purpose.
 */
public final class LoggerContext implements AutoCloseable {

    /** One config plus a cache of resolved levels. The cache belongs to the snapshot, so swapping the snapshot also throws the cache away in the same atomic step. */
    static final class Snapshot {
        final LogConfig config;
        final ConcurrentHashMap<String, Level> resolved = new ConcurrentHashMap<>();

        /** Wraps a config with an empty cache. */
        Snapshot(LogConfig config) {
            this.config = config;
        }

        /** Finds the effective level for a logger name: itself, then each parent, then the root. Cached after the first lookup. */
        Level levelFor(String name) {
            Level cached = resolved.get(name);
            if (cached != null) {
                return cached;
            }
            return resolved.computeIfAbsent(name, this::resolve);
        }

        /** Walks up the dotted name until some level is set. */
        private Level resolve(String name) {
            String n = name;
            while (true) {
                Level l = config.explicit(n);
                if (l != null) {
                    return l;
                }
                int dot = n.lastIndexOf('.');
                if (dot < 0) {
                    return config.root();
                }
                n = n.substring(0, dot);
            }
        }
    }

    private final Clock clock;
    private final AtomicReference<Snapshot> current;
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final AtomicLong failures = new AtomicLong();
    private final ConcurrentHashMap<Appender, AtomicLong> failuresByAppender = new ConcurrentHashMap<>();
    private final AtomicLong reentrantDrops = new AtomicLong();
    private final ThreadLocal<Boolean> writing = new ThreadLocal<>();
    private volatile Throwable lastFailure;

    /** Creates a context that stamps lines with {@code clock} and starts with {@code config}. */
    public LoggerContext(Clock clock, LogConfig config) {
        this.clock = Objects.requireNonNull(clock);
        this.current = new AtomicReference<>(new Snapshot(Objects.requireNonNull(config)));
    }

    /** Returns the logger with this name, creating it on first use. The same name always gives the same instance. */
    public Logger logger(String name) {
        return loggers.computeIfAbsent(name, n -> new Logger(n, this));
    }

    /** Returns the logger named after the class's full name. */
    public Logger logger(Class<?> type) {
        return logger(type.getName());
    }

    /**
     * Swaps in a new config in one atomic step.
     *
     * <p>Loggers read the snapshot once per call and pass it through both the level check and the dispatch, so a call in flight finishes entirely on the old config and the next call sees the new one. Appenders of the old config are not closed (the caller owns them), because they may be reused by the new config.
     *
     * <p>Failure counters of appenders that are not in the new config are forgotten.
     */
    public void reload(LogConfig newConfig) {
        current.set(new Snapshot(Objects.requireNonNull(newConfig)));
        failuresByAppender.keySet().retainAll(newConfig.appenders());
    }

    /** Returns the config in effect right now. */
    public LogConfig config() {
        return current.get().config;
    }

    /** Reads the current snapshot. A logger calls this once per log call and passes the result on, so the whole call uses one config. */
    Snapshot snapshot() {
        return current.get();
    }

    /** Hot path: is this logger enabled for this level in this snapshot? One map read, no allocation. */
    boolean enabled(Snapshot snapshot, String loggerName, Level level) {
        return level.passes(snapshot.levelFor(loggerName));
    }

    /** Returns true if the logger is enabled in the config in effect right now. For callers who only ask, not log. */
    boolean enabled(String loggerName, Level level) {
        return enabled(current.get(), loggerName, level);
    }

    /**
     * Builds the event on the caller's thread (so thread name and MDC are the caller's) and sends it to every appender of the given snapshot.
     *
     * <p>Nothing here may throw into business code: a re-entrant call is dropped and counted, and a failure while building the event is counted like an appender failure.
     */
    void dispatch(Snapshot snapshot, String loggerName, Level level, String message, Throwable error, Map<String, Object> fields) {
        if (writing.get() != null) {
            reentrantDrops.incrementAndGet();
            return;
        }
        writing.set(Boolean.TRUE);
        try {
            LogEvent event;
            try {
                event = new LogEvent(clock.instant(), level, loggerName, Thread.currentThread().getName(), message, error, Mdc.copy(), fields);
            } catch (RuntimeException e) {
                recordFailure(null, e);
                return;
            }
            for (Appender a : snapshot.config.appenders()) {
                try {
                    a.append(event);
                } catch (RuntimeException e) {
                    recordFailure(a, e);
                }
            }
        } finally {
            writing.remove();
        }
    }

    /** Counts one failure, against an appender when there is one, and remembers the exception. */
    private void recordFailure(Appender appender, RuntimeException e) {
        failures.incrementAndGet();
        if (appender != null) {
            failuresByAppender.computeIfAbsent(appender, k -> new AtomicLong()).incrementAndGet();
        }
        lastFailure = e;
    }

    /** Returns how many events were dropped because an appender logged through this same context while it was already writing (see the re-entrancy guard in dispatch). */
    public long reentrantDrops() {
        return reentrantDrops.get();
    }

    /** Returns how many times any appender threw since this context was created. */
    public long failureCount() {
        return failures.get();
    }

    /** Returns how many times this particular appender threw. */
    public long failuresFor(Appender appender) {
        AtomicLong c = failuresByAppender.get(appender);
        return c == null ? 0 : c.get();
    }

    /** Returns the most recent appender exception, or null if none happened. Handy for a health check. */
    public Throwable lastFailure() {
        return lastFailure;
    }

    /** Closes every appender of the current config. A failing close is counted like a failing append and does not stop the others. */
    @Override
    public void close() {
        for (Appender a : current.get().config.appenders()) {
            try {
                a.close();
            } catch (RuntimeException e) {
                recordFailure(a, e);
            }
        }
    }
}
