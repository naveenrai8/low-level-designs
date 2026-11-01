package com.lld.logging;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Writes each formatted event as one line to any {@link Appendable}: {@code System.out}, a file writer, a StringBuilder.
 *
 * <p>Close flushes the target if it is {@link Flushable}, so the tail of a file log is not lost. It also closes the target if it is {@link Closeable} and this appender was told it owns it. By default it does not own it (the caller may share {@code System.out}).
 *
 * <p>One line is written as one {@code append} of text plus newline under this appender's lock, so lines from different threads never interleave through the same {@code WriterAppender}. Two appenders sharing one target are not coordinated by this class.
 */
public final class WriterAppender implements Appender {

    private final Appendable out;
    private final LogFormatter formatter;
    private final boolean owns;
    private boolean closed;

    /** Creates an appender that formats with {@code formatter} and writes to {@code out}. It does not own {@code out}, so close only flushes it. */
    public WriterAppender(Appendable out, LogFormatter formatter) {
        this(out, formatter, false);
    }

    /** Creates an appender that also closes {@code out} on close when {@code closeTarget} is true and the target is {@link Closeable}. */
    public WriterAppender(Appendable out, LogFormatter formatter, boolean closeTarget) {
        this.out = Objects.requireNonNull(out);
        this.formatter = Objects.requireNonNull(formatter);
        this.owns = closeTarget;
    }

    /** Writes one line. Synchronized so two threads never interleave their characters. A write failure becomes an {@link UncheckedIOException}, which the context counts. */
    @Override
    public synchronized void append(LogEvent event) {
        try {
            out.append(formatter.format(event) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Flushes the target, and closes it if owned. Safe to call twice: the second call does nothing. A failure becomes an {@link UncheckedIOException}. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (out instanceof Flushable f) {
                f.flush();
            }
            if (owns && out instanceof Closeable c) {
                c.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
