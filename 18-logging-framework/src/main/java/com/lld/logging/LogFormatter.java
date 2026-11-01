package com.lld.logging;

/** Turns a {@link LogEvent} into one line of text. Kept separate from {@link Appender} so any destination can use any format. */
@FunctionalInterface
public interface LogFormatter {

    /** Returns the text for the event, without a trailing newline. */
    String format(LogEvent event);
}
