package com.lld.logging;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable description of how logging behaves: the default level, per-logger levels and where lines go.
 *
 * <p>Immutable on purpose. A reload builds a whole new config and swaps it in with one atomic write, so a thread logging at that moment sees either the old config or the new one, never a mix.
 *
 * <p>Level rule: a logger uses its own level if one is set, otherwise its closest ancestor's ("a.b.c" asks "a.b.c", then "a.b", then "a"), otherwise the root level.
 */
public final class LogConfig {

    private final Level root;
    private final Map<String, Level> levels;
    private final List<Appender> appenders;

    /** Takes defensive copies so the config can never change after construction. */
    private LogConfig(Level root, Map<String, Level> levels, List<Appender> appenders) {
        this.root = root;
        this.levels = Collections.unmodifiableMap(new HashMap<>(levels));
        this.appenders = List.copyOf(appenders);
    }

    /** Creates a config with only a root level and the given appenders. */
    public static LogConfig of(Level root, Appender... appenders) {
        return new LogConfig(Objects.requireNonNull(root), Map.of(), List.of(appenders));
    }

    /**
     * Reads levels from text, one {@code name=LEVEL} per line, where the name {@code root} sets the default level. Blank lines and lines starting with # are ignored.
     *
     * <p>All-or-nothing: any bad line throws {@link IllegalArgumentException} and nothing is built, so a reload from bad text leaves the running config untouched.
     */
    public static LogConfig parse(String text, Appender... appenders) {
        Level root = Level.INFO;
        Map<String, Level> levels = new HashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("bad config line: " + line);
            }
            String name = line.substring(0, eq).trim();
            Level level = Level.parse(line.substring(eq + 1));
            if (name.equals("root")) {
                root = level;
            } else {
                levels.put(name, level);
            }
        }
        return new LogConfig(root, levels, List.of(appenders));
    }

    /** Returns a new config with one more per-logger level. This config is unchanged. */
    public LogConfig withLevel(String loggerName, Level level) {
        Map<String, Level> copy = new HashMap<>(levels);
        copy.put(Objects.requireNonNull(loggerName), Objects.requireNonNull(level));
        return new LogConfig(root, copy, appenders);
    }

    /** Returns a new config with a different set of appenders. This config is unchanged. */
    public LogConfig withAppenders(Appender... newAppenders) {
        return new LogConfig(root, levels, List.of(newAppenders));
    }

    /** Returns the default level used when no ancestor has one. */
    public Level root() {
        return root;
    }

    /** Returns the appenders, as an immutable list. */
    public List<Appender> appenders() {
        return appenders;
    }

    /** Returns the level set exactly on this name, or null if none. */
    Level explicit(String name) {
        return levels.get(name);
    }
}
