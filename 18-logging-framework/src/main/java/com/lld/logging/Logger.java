package com.lld.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What application code calls. One logger per name; it asks the context whether its level is enabled, and only then builds the message.
 *
 * <p>Cost when a level is disabled: one snapshot read, one map read and a compare. The logger itself builds no message string, calls no supplier and allocates nothing. That is measured only for the non-capturing forms {@code debug("constant")} and {@code event(level).field("k", smallCachedValue).log("constant")}. A capturing lambda, or a boxed number above the Long cache, is allocated by the caller before the logger is entered, so guard those with {@link #isEnabled}.
 *
 * <p>Each log call reads the config snapshot once and uses it for both the level check and the dispatch, so a reload in between cannot send a line to the wrong config's appenders.
 */
public final class Logger {

    private static final Map<String, Object> NO_FIELDS = Map.of();

    private final String name;
    private final LoggerContext context;

    /** Created only by {@link LoggerContext#logger(String)}. */
    Logger(String name, LoggerContext context) {
        this.name = name;
        this.context = context;
    }

    /** Returns the logger's name, such as "shop.orders". */
    public String name() {
        return name;
    }

    /** Returns true if a line at this level would be written. Use it to guard work that is more than a message. */
    public boolean isEnabled(Level level) {
        return context.enabled(name, level);
    }

    /** Logs a fixed message. */
    public void log(Level level, String message) {
        LoggerContext.Snapshot snap = context.snapshot();
        if (context.enabled(snap, name, level)) {
            context.dispatch(snap, name, level, message, null, NO_FIELDS);
        }
    }

    /** Logs a message that is built lazily: the supplier runs only if the level is enabled. */
    public void log(Level level, Supplier<String> message) {
        LoggerContext.Snapshot snap = context.snapshot();
        if (context.enabled(snap, name, level)) {
            context.dispatch(snap, name, level, message.get(), null, NO_FIELDS);
        }
    }

    /** Logs a message and an exception. */
    public void log(Level level, String message, Throwable error) {
        LoggerContext.Snapshot snap = context.snapshot();
        if (context.enabled(snap, name, level)) {
            context.dispatch(snap, name, level, message, error, NO_FIELDS);
        }
    }

    /** Logs a template where {@code {}} is replaced by the argument. The text is joined only if the level is enabled. */
    public void log(Level level, String template, Object arg) {
        LoggerContext.Snapshot snap = context.snapshot();
        if (context.enabled(snap, name, level)) {
            context.dispatch(snap, name, level, fill(template, arg), null, NO_FIELDS);
        }
    }

    /** Logs a template with two {@code {}} placeholders. */
    public void log(Level level, String template, Object a, Object b) {
        LoggerContext.Snapshot snap = context.snapshot();
        if (context.enabled(snap, name, level)) {
            context.dispatch(snap, name, level, fill(fill(template, a), b), null, NO_FIELDS);
        }
    }

    /** Replaces the first {@code {}} in the template with the argument. If there is none, returns the template unchanged. */
    private static String fill(String template, Object arg) {
        int i = template.indexOf("{}");
        return i < 0 ? template : template.substring(0, i) + arg + template.substring(i + 2);
    }

    /**
     * Starts a structured line: add fields with {@link Event#field}, then call {@link Event#log(String)}.
     *
     * <p>When the level is disabled this returns one shared do-nothing object, so a disabled structured line allocates nothing.
     */
    public Event event(Level level) {
        LoggerContext.Snapshot snap = context.snapshot();
        return context.enabled(snap, name, level) ? new Event(this, level, snap) : Event.DISABLED;
    }

    /** A structured line being built: a level, some key/value fields and optionally an exception. */
    public static final class Event {
        private static final Event DISABLED = new Event(null, null, null);

        private final Logger logger;
        private final Level level;
        private final LoggerContext.Snapshot snapshot;
        private Map<String, Object> fields;
        private Throwable error;

        /** Creates a builder. A null logger makes the shared disabled one. The snapshot is the one the level check used, and the final write uses it too. */
        private Event(Logger logger, Level level, LoggerContext.Snapshot snapshot) {
            this.logger = logger;
            this.level = level;
            this.snapshot = snapshot;
        }

        /** Adds a key/value field to this line. Ignored when disabled. A null key is skipped, because a map cannot hold it and logging must never throw into the caller. */
        public Event field(String key, Object value) {
            if (logger != null && key != null) {
                if (fields == null) {
                    fields = new LinkedHashMap<>();
                }
                fields.put(key, value);
            }
            return this;
        }

        /** Attaches an exception to this line. Ignored when disabled. */
        public Event error(Throwable t) {
            if (logger != null) {
                error = t;
            }
            return this;
        }

        /** Writes the line. Does nothing when disabled. */
        public void log(String message) {
            if (logger != null) {
                logger.context.dispatch(snapshot, logger.name, level, message, error, fields == null ? NO_FIELDS : fields);
            }
        }
    }

    /** Logs a fixed message at TRACE. */
    public void trace(String message) {
        log(Level.TRACE, message);
    }

    /** Logs a lazily built message at TRACE. */
    public void trace(Supplier<String> message) {
        log(Level.TRACE, message);
    }

    /** Logs a message and an exception at TRACE. */
    public void trace(String message, Throwable error) {
        log(Level.TRACE, message, error);
    }

    /** Logs a template with one {} placeholder at TRACE. */
    public void trace(String template, Object arg) {
        log(Level.TRACE, template, arg);
    }

    /** Logs a fixed message at DEBUG. */
    public void debug(String message) {
        log(Level.DEBUG, message);
    }

    /** Logs a lazily built message at DEBUG. */
    public void debug(Supplier<String> message) {
        log(Level.DEBUG, message);
    }

    /** Logs a message and an exception at DEBUG. */
    public void debug(String message, Throwable error) {
        log(Level.DEBUG, message, error);
    }

    /** Logs a template with one {} placeholder at DEBUG. */
    public void debug(String template, Object arg) {
        log(Level.DEBUG, template, arg);
    }

    /** Logs a fixed message at INFO. */
    public void info(String message) {
        log(Level.INFO, message);
    }

    /** Logs a lazily built message at INFO. */
    public void info(Supplier<String> message) {
        log(Level.INFO, message);
    }

    /** Logs a message and an exception at INFO. */
    public void info(String message, Throwable error) {
        log(Level.INFO, message, error);
    }

    /** Logs a template with one {} placeholder at INFO. */
    public void info(String template, Object arg) {
        log(Level.INFO, template, arg);
    }

    /** Logs a fixed message at WARN. */
    public void warn(String message) {
        log(Level.WARN, message);
    }

    /** Logs a lazily built message at WARN. */
    public void warn(Supplier<String> message) {
        log(Level.WARN, message);
    }

    /** Logs a message and an exception at WARN. */
    public void warn(String message, Throwable error) {
        log(Level.WARN, message, error);
    }

    /** Logs a template with one {} placeholder at WARN. */
    public void warn(String template, Object arg) {
        log(Level.WARN, template, arg);
    }

    /** Logs a fixed message at ERROR. */
    public void error(String message) {
        log(Level.ERROR, message);
    }

    /** Logs a lazily built message at ERROR. */
    public void error(Supplier<String> message) {
        log(Level.ERROR, message);
    }

    /** Logs a message and an exception at ERROR. */
    public void error(String message, Throwable error) {
        log(Level.ERROR, message, error);
    }

    /** Logs a template with one {} placeholder at ERROR. */
    public void error(String template, Object arg) {
        log(Level.ERROR, template, arg);
    }
}
