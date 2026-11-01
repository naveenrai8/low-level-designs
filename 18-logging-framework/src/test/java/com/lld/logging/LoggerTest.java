package com.lld.logging;

import static org.junit.jupiter.api.Assertions.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests for levels, formatting, appender isolation, lazy messages and the hierarchy. Each test has a timeout, so a hang fails instead of blocking the build. */
@Timeout(20)
class LoggerTest {

    private final MemoryAppender mem = new MemoryAppender();

    /** Builds a context with the root level and the memory appender plus any extras. */
    private LoggerContext ctx(Level root, Appender... extra) {
        Appender[] all = new Appender[extra.length + 1];
        all[0] = mem;
        System.arraycopy(extra, 0, all, 1, extra.length);
        return new LoggerContext(TestSupport.CLOCK, LogConfig.of(root, all));
    }

    /** Lines below the threshold are dropped, lines at or above it are written. */
    @Test
    void levelFiltering() {
        Logger log = ctx(Level.WARN).logger("a");
        log.info("no");
        log.warn("yes");
        log.error("yes2");
        assertEquals(java.util.List.of("yes", "yes2"), mem.messages());
    }

    /** OFF blocks everything, even ERROR. */
    @Test
    void offBlocksEverything() {
        Logger log = ctx(Level.OFF).logger("a");
        log.error("x");
        assertTrue(mem.events().isEmpty());
    }

    /** Timestamps come from the injected clock. */
    @Test
    void timestampComesFromClock() {
        ctx(Level.INFO).logger("a").info("x");
        assertEquals(TestSupport.CLOCK.instant(), mem.events().get(0).time());
    }

    /** A bad level name in text is rejected instead of defaulting. */
    @Test
    void levelParseRejectsTypos() {
        assertEquals(Level.WARN, Level.parse(" warn "));
        assertThrows(IllegalArgumentException.class, () -> Level.parse("loud"));
        assertThrows(IllegalArgumentException.class, () -> Level.parse(null));
    }

    /** A throwing appender does not stop the next one, does not reach the caller, and is counted. */
    @Test
    void brokenAppenderIsIsolatedAndCounted() {
        Appender broken = new TestSupport.Broken();
        MemoryAppender after = new MemoryAppender();
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, broken, mem, after));
        Logger log = c.logger("a");
        assertDoesNotThrow(() -> {
            log.info("one");
            log.info("two");
        });
        assertEquals(2, mem.events().size());
        assertEquals(2, after.events().size());
        assertEquals(2, c.failureCount());
        assertEquals(2, c.failuresFor(broken));
        assertEquals(0, c.failuresFor(mem));
        assertEquals("disk full", c.lastFailure().getMessage());
    }

    /** A formatter that throws inside an appender is handled the same way as any appender failure. */
    @Test
    void formatterFailureIsIsolated() {
        Appender bad = new WriterAppender(new StringBuilder(), e -> {
            throw new IllegalArgumentException("bad format");
        });
        LoggerContext c = ctx(Level.INFO, bad);
        assertDoesNotThrow(() -> c.logger("a").info("x"));
        assertEquals(1, c.failureCount());
        assertEquals(1, mem.events().size());
    }

    /** A failing close of one appender does not stop the others from closing. */
    @Test
    void closeIsolatesFailures() {
        AtomicInteger closed = new AtomicInteger();
        Appender badClose = new Appender() {
            /** Ignores events. */
            @Override
            public void append(LogEvent e) {
            }

            /** Fails when closed. */
            @Override
            public void close() {
                throw new IllegalStateException("close");
            }
        };
        Appender counting = new Appender() {
            /** Ignores events. */
            @Override
            public void append(LogEvent e) {
            }

            /** Counts closes. */
            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, badClose, counting));
        c.close();
        assertEquals(1, closed.get());
        assertEquals(1, c.failureCount());
    }

    /** A disabled level never calls the supplier, so expensive messages cost nothing. */
    @Test
    void disabledSupplierIsNotCalled() {
        Logger log = ctx(Level.WARN).logger("a");
        AtomicInteger calls = new AtomicInteger();
        log.debug(() -> {
            calls.incrementAndGet();
            return "x";
        });
        assertEquals(0, calls.get());
        log.error(() -> {
            calls.incrementAndGet();
            return "y";
        });
        assertEquals(1, calls.get());
        assertEquals(java.util.List.of("y"), mem.messages());
    }

    /** The {} template is filled in for an enabled level and an argument's toString is not called for a disabled one. */
    @Test
    void templateIsLazy() {
        Logger log = ctx(Level.INFO).logger("a");
        AtomicInteger calls = new AtomicInteger();
        Object arg = new Object() {
            /** Counts how often it is turned into text. */
            @Override
            public String toString() {
                calls.incrementAndGet();
                return "V";
            }
        };
        log.debug("value={}", arg);
        assertEquals(0, calls.get());
        log.info("value={}", arg);
        assertEquals("value=V", mem.messages().get(0));
    }

    /**
     * The disabled path allocates (almost) nothing, measured with the thread allocation counter, for the two non-capturing forms only: a constant message and a structured line with a small cached boxed value.
     * <p>A capturing lambda or a boxed value above the Long cache is allocated by the caller before the logger is entered, so those are not covered. Skipped on a JVM that cannot measure.
     */
    @Test
    void disabledPathAllocatesNothing() {
        Logger log = ctx(Level.WARN).logger("a.b.c");
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assumeTrue(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled(), "thread allocation counter not available");
        long id = Thread.currentThread().threadId();
        for (int i = 0; i < 20_000; i++) {
            log.debug("warm");
            log.event(Level.DEBUG).field("k", 1).log("warm");
        }
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 100_000; i++) {
            log.debug("x");
            log.event(Level.DEBUG).field("k", 1).log("x");
        }
        long used = mx.getThreadAllocatedBytes(id) - before;
        assertTrue(used < 4_096, "allocated " + used + " bytes");
    }

    /** A child with no level inherits from its closest ancestor; its own level wins; unrelated names use the root. */
    @Test
    void hierarchyInheritance() {
        LogConfig cfg = LogConfig.of(Level.ERROR, mem).withLevel("a", Level.INFO).withLevel("a.b", Level.DEBUG).withLevel("a.b.x", Level.WARN);
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, cfg);
        assertTrue(c.logger("a.b.c").isEnabled(Level.DEBUG));
        assertFalse(c.logger("a.b.x").isEnabled(Level.INFO));
        assertTrue(c.logger("a.z").isEnabled(Level.INFO));
        assertFalse(c.logger("a.z").isEnabled(Level.DEBUG));
        assertFalse(c.logger("other").isEnabled(Level.WARN));
        assertTrue(c.logger("other").isEnabled(Level.ERROR));
        assertFalse(c.logger("ab").isEnabled(Level.INFO), "ab is not a child of a");
    }

    /** Reload takes effect for existing logger objects, and a bad config text leaves the running config untouched. */
    @Test
    void reloadSwapsAndBadTextKeepsOld() {
        LoggerContext c = ctx(Level.ERROR);
        Logger log = c.logger("a.b");
        log.info("lost");
        c.reload(LogConfig.parse("root=ERROR\n# comment\na=INFO\n", mem));
        log.info("kept");
        assertEquals(java.util.List.of("kept"), mem.messages());
        assertThrows(IllegalArgumentException.class, () -> LogConfig.parse("a=INFO\nb=nope", mem));
        assertThrows(IllegalArgumentException.class, () -> LogConfig.parse("garbage", mem));
        log.debug("still off");
        assertEquals(1, mem.events().size());
    }

    /** A clock that reloads the config the first time it is asked for the time. Dispatch asks for the time after the level check and before it reads the appenders, so this forces a reload into exactly that gap on one thread. */
    private static final class ReloadingClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Runnable> once = new java.util.concurrent.atomic.AtomicReference<>();

        /** Sets the action to run the first time the time is read. */
        void onFirstRead(Runnable action) {
            once.set(action);
        }

        /** Returns the fixed zone. */
        @Override
        public ZoneId getZone() {
            return TestSupport.CLOCK.getZone();
        }

        /** Returns this same clock; the zone never matters here. */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** Runs the pending action once (the reload), then returns the fixed time. */
        @Override
        public Instant instant() {
            Runnable r = once.getAndSet(null);
            if (r != null) {
                r.run();
            }
            return TestSupport.CLOCK.instant();
        }
    }

    /** One log call must use one config snapshot: a DEBUG line that passed config A's level check goes to A's appenders even if a reload to B (where x=ERROR) lands in between. */
    @Test
    void oneCallUsesOneSnapshot() {
        MemoryAppender memA = new MemoryAppender();
        MemoryAppender memB = new MemoryAppender();
        LogConfig a = LogConfig.of(Level.ERROR, memA).withLevel("x", Level.DEBUG);
        LogConfig b = LogConfig.of(Level.ERROR, memB).withLevel("x", Level.ERROR);
        ReloadingClock clock = new ReloadingClock();
        LoggerContext c = new LoggerContext(clock, a);
        clock.onFirstRead(() -> c.reload(b));
        c.logger("x").debug("passed under A");
        assertEquals(java.util.List.of("passed under A"), memA.messages());
        assertTrue(memB.events().isEmpty(), "config B says x=ERROR, so it must never see a DEBUG line");
        c.logger("x").debug("next call sees B");
        assertEquals(1, memA.events().size());
        assertTrue(memB.events().isEmpty());
    }

    /** The same rule for the structured builder: the snapshot is taken when the builder starts and used again at log(). */
    @Test
    void structuredCallUsesOneSnapshot() {
        MemoryAppender memA = new MemoryAppender();
        MemoryAppender memB = new MemoryAppender();
        LogConfig a = LogConfig.of(Level.ERROR, memA).withLevel("x", Level.DEBUG);
        LogConfig b = LogConfig.of(Level.ERROR, memB).withLevel("x", Level.ERROR);
        ReloadingClock clock = new ReloadingClock();
        LoggerContext c = new LoggerContext(clock, a);
        clock.onFirstRead(() -> c.reload(b));
        c.logger("x").event(Level.DEBUG).field("k", 1).log("passed under A");
        assertEquals(1, memA.events().size());
        assertTrue(memB.events().isEmpty());
    }

    /** Stress companion to the probe: while the main thread flips between a config that allows DEBUG and one that allows only ERROR, the logging thread's DEBUG lines must never land in the second config's appender. */
    @Test
    void reloadStressNeverLeaksIntoOtherConfig() throws Exception {
        MemoryAppender memA = new MemoryAppender();
        MemoryAppender memB = new MemoryAppender();
        LogConfig a = LogConfig.of(Level.ERROR, memA).withLevel("x", Level.DEBUG);
        LogConfig b = LogConfig.of(Level.ERROR, memB).withLevel("x", Level.ERROR);
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, a);
        Logger log = c.logger("x");
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                log.debug("d");
            }
        });
        writer.start();
        for (int i = 0; i < 20_000; i++) {
            c.reload(i % 2 == 0 ? b : a);
        }
        stop.set(true);
        writer.join();
        assertTrue(memB.events().isEmpty(), "config B must never receive a DEBUG line");
    }

    /** The pattern formatter prints MDC and fields; the JSON formatter escapes and keeps numbers as numbers. */
    @Test
    void formatters() {
        LogEvent e = new LogEvent(TestSupport.CLOCK.instant(), Level.WARN, "a.b", "main", "say \"hi\"\n", new IllegalStateException("boom"), java.util.Map.of("req", "7"), java.util.Map.of("amount", 500, "ok", true, "who", "bob"));
        assertEquals("2026-01-01T00:00:00Z WARN [main] a.b - say \"hi\"\\n {req=7} amount=500 ok=true who=bob | java.lang.IllegalStateException: boom", new PatternFormatter().format(e));
        assertEquals("{\"ts\":\"2026-01-01T00:00:00Z\",\"level\":\"WARN\",\"logger\":\"a.b\",\"thread\":\"main\",\"msg\":\"say \\\"hi\\\"\\n\",\"mdc\":{\"req\":\"7\"},\"fields\":{\"amount\":500,\"ok\":true,\"who\":\"bob\"},\"error\":\"java.lang.IllegalStateException: boom\"}", new JsonFormatter().format(e));
    }

    /** A structured event carries its fields to the appender. */
    @Test
    void structuredFields() {
        Logger log = ctx(Level.INFO).logger("pay");
        log.event(Level.INFO).field("amount", 500L).field("currency", "INR").log("paid");
        assertEquals(500L, mem.events().get(0).fields().get("amount"));
        assertEquals("INR", mem.events().get(0).fields().get("currency"));
    }

    /** A writer appender writes one formatted line per event to its target. */
    @Test
    void writerAppenderWritesLines() {
        StringBuilder sb = new StringBuilder();
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, new WriterAppender(sb, new PatternFormatter())));
        c.logger("a").info("hello");
        assertEquals("2026-01-01T00:00:00Z INFO [" + Thread.currentThread().getName() + "] a - hello\n", sb.toString());
    }

    /** An Appendable that always fails with a checked IOException, like a closed pipe. */
    private static final class FailingOut implements Appendable {
        /** Always throws. */
        @Override
        public Appendable append(CharSequence csq) throws IOException {
            throw new IOException("pipe closed");
        }

        /** Always throws. */
        @Override
        public Appendable append(CharSequence csq, int start, int end) throws IOException {
            throw new IOException("pipe closed");
        }

        /** Always throws. */
        @Override
        public Appendable append(char c) throws IOException {
            throw new IOException("pipe closed");
        }
    }

    /** A writer failure becomes an UncheckedIOException, is counted by the context and never reaches the caller. */
    @Test
    void writerFailureIsCountedNotThrown() {
        LoggerContext c = ctx(Level.INFO, new WriterAppender(new FailingOut(), new PatternFormatter()));
        assertDoesNotThrow(() -> c.logger("a").info("x"));
        assertEquals(1, c.failureCount());
        UncheckedIOException e = assertInstanceOf(UncheckedIOException.class, c.lastFailure());
        assertEquals("pipe closed", e.getCause().getMessage());
        assertEquals(1, mem.events().size());
    }

    /** A target that records flush and close calls. */
    private static final class Probe implements Appendable, Flushable, java.io.Closeable {
        final StringBuilder text = new StringBuilder();
        int flushes;
        int closes;

        /** Collects text. */
        @Override
        public Appendable append(CharSequence csq) {
            text.append(csq);
            return this;
        }

        /** Collects text. */
        @Override
        public Appendable append(CharSequence csq, int start, int end) {
            text.append(csq, start, end);
            return this;
        }

        /** Collects text. */
        @Override
        public Appendable append(char c) {
            text.append(c);
            return this;
        }

        /** Counts flushes. */
        @Override
        public void flush() {
            flushes++;
        }

        /** Counts closes. */
        @Override
        public void close() {
            closes++;
        }
    }

    /** Close flushes a Flushable target so the tail of a file log is not lost, and leaves it open when the appender does not own it. */
    @Test
    void writerAppenderCloseFlushesButDoesNotCloseBorrowedTarget() {
        Probe p = new Probe();
        WriterAppender w = new WriterAppender(p, new PatternFormatter());
        w.append(TestSupport.event("x"));
        w.close();
        w.close();
        assertTrue(p.flushes >= 1);
        assertEquals(0, p.closes);
    }

    /** When told it owns the target, close flushes then closes it exactly once, even if close is called twice. */
    @Test
    void writerAppenderClosesOwnedTargetOnce() {
        Probe p = new Probe();
        WriterAppender w = new WriterAppender(p, new PatternFormatter(), true);
        w.append(TestSupport.event("x"));
        w.close();
        w.close();
        assertTrue(p.flushes >= 1);
        assertEquals(1, p.closes);
    }

    /** NaN and Infinity are not valid JSON numbers, so they are written as strings. */
    @Test
    void jsonQuotesNonFiniteNumbers() {
        LogEvent e = new LogEvent(TestSupport.CLOCK.instant(), Level.INFO, "a", "main", "m", null, java.util.Map.of(), java.util.Map.of("n", Double.NaN, "i", Double.POSITIVE_INFINITY, "f", Float.NEGATIVE_INFINITY, "ok", 1.5));
        String json = new JsonFormatter().format(e);
        assertTrue(json.contains("\"fields\":{\"f\":\"-Infinity\",\"i\":\"Infinity\",\"n\":\"NaN\",\"ok\":1.5}"), json);
    }

    /** A custom Number whose toString contains JSON syntax cannot inject keys: only JDK numeric types are written raw. */
    @Test
    void jsonDoesNotTrustCustomNumberToString() {
        Number evil = new Number() {
            /** Not used. */
            @Override
            public int intValue() {
                return 0;
            }

            /** Not used. */
            @Override
            public long longValue() {
                return 0;
            }

            /** Not used. */
            @Override
            public float floatValue() {
                return 0;
            }

            /** Not used. */
            @Override
            public double doubleValue() {
                return 0;
            }

            /** Tries to close the object and add a key. */
            @Override
            public String toString() {
                return "1},\"injected\":{\"x\":2";
            }
        };
        LogEvent e = new LogEvent(TestSupport.CLOCK.instant(), Level.INFO, "a", "main", "m", null, java.util.Map.of(), java.util.Map.of("n", evil));
        String json = new JsonFormatter().format(e);
        assertTrue(json.contains("\"n\":\"1},\\\"injected\\\":{\\\"x\\\":2\""), json);
    }

    /** A null message is written as JSON null instead of throwing. */
    @Test
    void jsonWritesNullMessage() {
        LogEvent e = new LogEvent(TestSupport.CLOCK.instant(), Level.INFO, "a", "main", null, null, java.util.Map.of(), java.util.Map.of());
        assertTrue(new JsonFormatter().format(e).contains("\"msg\":null"));
    }

    /** A null field key is skipped (documented), so it can neither throw into the caller nor reach an appender. */
    @Test
    void nullFieldKeyIsSkipped() {
        Logger log = ctx(Level.INFO).logger("a");
        assertDoesNotThrow(() -> log.event(Level.INFO).field(null, 1).field("k", 2).log("x"));
        assertEquals(1, mem.events().size());
        assertEquals(java.util.Map.of("k", 2), mem.events().get(0).fields());
    }

    /** An appender that logs through the same context would recurse forever. The guard drops the inner event, counts it, and the caller sees no error. */
    @Test
    void reentrantLoggingFromAppenderIsDroppedAndCounted() {
        java.util.concurrent.atomic.AtomicReference<Logger> self = new java.util.concurrent.atomic.AtomicReference<>();
        Appender chatty = e -> self.get().info("appender chatter");
        LoggerContext c = ctx(Level.INFO, chatty);
        self.set(c.logger("a"));
        assertDoesNotThrow(() -> c.logger("a").info("outer"));
        assertEquals(java.util.List.of("outer"), mem.messages());
        assertEquals(1, c.reentrantDrops());
        c.logger("a").info("again");
        assertEquals(2, c.reentrantDrops());
        assertEquals(java.util.List.of("outer", "again"), mem.messages());
    }

    /** A CR or LF in a message cannot start a fake log line (CWE-117): the pattern formatter writes it as the two characters backslash-n. */
    @Test
    void patternFormatterEscapesLineBreaks() {
        LogEvent e = new LogEvent(TestSupport.CLOCK.instant(), Level.INFO, "a", "main", "ok\r\n2026-01-01T00:00:00Z ERROR [x] forged - hi", null, java.util.Map.of("k", "v\nw"), java.util.Map.of("f", "a\nb"));
        String line = new PatternFormatter().format(e);
        assertFalse(line.contains("\n") || line.contains("\r"), line);
        assertTrue(line.contains("ok\\r\\n2026"), line);
        assertTrue(line.contains("k=v\\nw") && line.contains("f=a\\nb"), line);
    }

    /** After a reload, failure counts for appenders that are no longer configured are forgotten, so the map cannot keep dead appenders alive; the total stays. */
    @Test
    void reloadForgetsFailureCountsOfRemovedAppenders() {
        Appender broken = new TestSupport.Broken();
        LoggerContext c = new LoggerContext(TestSupport.CLOCK, LogConfig.of(Level.INFO, broken, mem));
        c.logger("a").info("x");
        assertEquals(1, c.failuresFor(broken));
        c.reload(LogConfig.of(Level.INFO, mem));
        assertEquals(0, c.failuresFor(broken));
        assertEquals(1, c.failureCount());
    }
}
