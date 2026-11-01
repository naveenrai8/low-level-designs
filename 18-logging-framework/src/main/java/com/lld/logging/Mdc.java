package com.lld.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * The Mapped Diagnostic Context: key/value labels (such as a request id) that ride along with the current thread and are stamped on every line it logs.
 *
 * <p>It is stored in a plain {@link ThreadLocal}. That is the catch: a ThreadLocal belongs to one thread, so a task handed to an executor runs on another thread and sees nothing. Use {@link #wrap(Runnable)}, {@link #wrap(Callable)} or {@link #executor(Executor)} to copy the labels over explicitly. The wrapper also restores the worker's own labels when the task ends, so a pooled thread never leaks one request's context into the next.
 */
public final class Mdc {

    private static final ThreadLocal<Map<String, String>> CURRENT = new ThreadLocal<>();

    /** Not instantiable: all methods are static. */
    private Mdc() {
    }

    /** Sets a label on the current thread. */
    public static void put(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Map<String, String> m = CURRENT.get();
        if (m == null) {
            m = new HashMap<>();
            CURRENT.set(m);
        }
        m.put(key, value);
    }

    /** Returns the label on the current thread, or null. */
    public static String get(String key) {
        Map<String, String> m = CURRENT.get();
        return m == null ? null : m.get(key);
    }

    /** Removes one label from the current thread. */
    public static void remove(String key) {
        Map<String, String> m = CURRENT.get();
        if (m != null) {
            m.remove(key);
            if (m.isEmpty()) {
                CURRENT.remove();
            }
        }
    }

    /** Removes every label from the current thread and frees the ThreadLocal slot. */
    public static void clear() {
        CURRENT.remove();
    }

    /** Returns an immutable copy of the current thread's labels. Empty (a shared constant, no allocation) when there are none. */
    public static Map<String, String> copy() {
        Map<String, String> m = CURRENT.get();
        return m == null ? Map.of() : Map.copyOf(m);
    }

    /** Replaces the current thread's labels with the given ones. An empty map clears them. */
    private static void restore(Map<String, String> labels) {
        if (labels.isEmpty()) {
            CURRENT.remove();
        } else {
            CURRENT.set(new HashMap<>(labels));
        }
    }

    /**
     * Captures the labels of the calling thread now, and returns a task that runs {@code task} with those labels on whatever thread runs it.
     *
     * <p>When the task ends (normally or by exception) the worker thread gets back the labels it had before, so nothing leaks to the next task.
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task);
        Map<String, String> captured = copy();
        return () -> {
            Map<String, String> before = copy();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(before);
            }
        };
    }

    /** Same as {@link #wrap(Runnable)} but for a task that returns a value. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Objects.requireNonNull(task);
        Map<String, String> captured = copy();
        return () -> {
            Map<String, String> before = copy();
            restore(captured);
            try {
                return task.call();
            } finally {
                restore(before);
            }
        };
    }

    /** Returns an executor that wraps every task with the submitting thread's labels, so callers cannot forget. */
    public static Executor executor(Executor delegate) {
        Objects.requireNonNull(delegate);
        return task -> delegate.execute(wrap(task));
    }

    /** A label that removes itself (restoring the old value) when closed. Use it in try-with-resources. */
    public static final class Scope implements AutoCloseable {
        private final String key;
        private final String old;

        /** Remembers the previous value of {@code key} and sets the new one. */
        private Scope(String key, String value) {
            this.key = key;
            this.old = get(key);
            put(key, value);
        }

        /** Puts the previous value back, or removes the label if there was none. */
        @Override
        public void close() {
            if (old == null) {
                remove(key);
            } else {
                put(key, old);
            }
        }
    }

    /** Sets a label for the duration of a try-with-resources block. */
    public static Scope with(String key, String value) {
        return new Scope(key, value);
    }
}
