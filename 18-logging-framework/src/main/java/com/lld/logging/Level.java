package com.lld.logging;

import java.util.Locale;

/**
 * How important a log line is, from least (TRACE) to most (ERROR).
 *
 * <p>A logger has a threshold. A line is written only if its level is at or above the threshold. {@link #OFF} is a threshold only: it switches a logger off completely, so nothing can be logged "at" OFF.
 */
public enum Level {
    /** Very fine detail, normally off. */
    TRACE,
    /** Detail useful while debugging. */
    DEBUG,
    /** Normal events worth recording. */
    INFO,
    /** Something odd happened but the work went on. */
    WARN,
    /** The work failed. */
    ERROR,
    /** Threshold that blocks every line. */
    OFF;

    /** Returns true if a line at this level passes the given threshold. Always false for OFF, because OFF is not a real line level. */
    public boolean passes(Level threshold) {
        return this != OFF && ordinal() >= threshold.ordinal();
    }

    /** Reads a level from text such as "info" or "WARN" (case does not matter). Throws {@link IllegalArgumentException} for anything else, so a typo in a config never silently becomes a default. */
    public static Level parse(String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown log level: " + text);
        }
    }
}
