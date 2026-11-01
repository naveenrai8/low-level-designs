package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests the write-ahead log seam: replay rebuilds state, torn tails are ignored, transactions are all-or-nothing. */
class WalTest {

    /** Recovery without a new log: ignores the valid length and returns no log. */
    private static final java.util.function.IntFunction<Wal> NO_LOG = valid -> null;

    private final ManualClock clock = new ManualClock();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final InMemoryKvStore store = new InMemoryKvStore(clock, new Wal(bytes));

    /** Replaying the log into a fresh store gives the same values, TTLs and deletes as the original. */
    @Test
    void replayRebuildsSameState() {
        store.set("a", "1");
        store.set("b", "2", Duration.ofSeconds(30));
        store.set("c", "3");
        store.delete("c");
        Session s = store.openSession();
        s.begin();
        s.set("d", "4");
        s.set("a", "9");
        s.commit();
        clock.advance(Duration.ofSeconds(10));
        InMemoryKvStore copy = InMemoryKvStore.recover(clock, bytes.toByteArray(), NO_LOG);
        assertEquals(store.get("a"), copy.get("a"));
        assertEquals(Optional.of("9"), copy.get("a"));
        assertEquals(store.ttl("b"), copy.ttl("b"));
        assertEquals(Optional.empty(), copy.get("c"));
        assertEquals(Optional.of("4"), copy.get("d"));
        assertEquals(store.count(), copy.count());
        clock.advance(Duration.ofSeconds(20));
        assertEquals(Optional.empty(), copy.get("b"));
    }

    /** A half-written last record (cut at every possible length) is ignored and all earlier records survive. */
    @Test
    void tornLastRecordIsIgnored() {
        store.set("a", "1");
        int goodLength = bytes.size();
        store.set("b", "2");
        byte[] full = bytes.toByteArray();
        for (int cut = goodLength; cut < full.length; cut++) {
            InMemoryKvStore copy = InMemoryKvStore.recover(clock, Arrays.copyOf(full, cut), NO_LOG);
            assertEquals(Optional.of("1"), copy.get("a"), "cut " + cut);
            assertEquals(Optional.empty(), copy.get("b"), "cut " + cut);
            assertEquals(goodLength, Wal.replay(Arrays.copyOf(full, cut)).validBytes(), "cut " + cut);
        }
        assertEquals(Optional.of("2"), InMemoryKvStore.recover(clock, full, NO_LOG).get("b"));
    }

    /** A flipped byte inside the last record fails its checksum, so that record is dropped. */
    @Test
    void corruptRecordIsIgnored() {
        store.set("a", "1");
        store.set("b", "2");
        byte[] log = bytes.toByteArray();
        log[log.length - 1] ^= 0x55;
        InMemoryKvStore copy = InMemoryKvStore.recover(clock, log, NO_LOG);
        assertEquals(Optional.of("1"), copy.get("a"));
        assertEquals(Optional.empty(), copy.get("b"));
    }

    /** A transaction is one log record, so a torn commit loses the whole transaction and never half of it. */
    @Test
    void tornTransactionIsAllOrNothing() {
        Session s = store.openSession();
        s.begin();
        s.set("x", "1");
        s.set("y", "2");
        s.commit();
        byte[] log = bytes.toByteArray();
        InMemoryKvStore torn = InMemoryKvStore.recover(clock, Arrays.copyOf(log, log.length - 3), NO_LOG);
        assertEquals(0, torn.count());
        assertEquals(2, InMemoryKvStore.recover(clock, log, NO_LOG).count());
    }

    /** A rolled-back transaction writes nothing to the log. */
    @Test
    void rollbackWritesNothing() {
        Session s = store.openSession();
        s.begin();
        s.set("x", "1");
        s.rollback();
        assertTrue(bytes.size() == 0);
    }

    /** An output that accepts a fixed number of bytes, then throws once in the middle of a write, then works again (a transient disk error). */
    private static final class FlakyOutput extends ByteArrayOutputStream {
        private long budget = Long.MAX_VALUE;

        /** Allows only {@code more} further bytes before the next write fails once. */
        void failAfter(long more) {
            budget = more;
        }

        /** Writes what fits in the budget, then throws once and resets so later writes would succeed. */
        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (len > budget) {
                super.write(b, off, (int) budget);
                budget = Long.MAX_VALUE;
                throw new java.io.UncheckedIOException(new java.io.IOException("disk error"));
            }
            budget -= len;
            super.write(b, off, len);
        }

        /** Single-byte write, routed through the budgeted array write. */
        @Override
        public synchronized void write(int b) {
            write(new byte[] {(byte) b}, 0, 1);
        }
    }

    /** After one failed append the log is fail-stop: later commits are rejected instead of being acknowledged behind torn bytes, and recovery sees a consistent prefix. */
    @Test
    void failedAppendMakesLogFailStop() {
        FlakyOutput out = new FlakyOutput();
        InMemoryKvStore flaky = new InMemoryKvStore(clock, new Wal(out));
        flaky.set("ok", "1");
        out.failAfter(3);
        assertThrows(java.io.UncheckedIOException.class, () -> flaky.set("a", "2"));
        assertThrows(RuntimeException.class, () -> flaky.set("c", "3"));
        assertEquals(Optional.empty(), flaky.get("a"));
        assertEquals(Optional.empty(), flaky.get("c"));
        InMemoryKvStore copy = InMemoryKvStore.recover(clock, out.toByteArray(), NO_LOG);
        assertEquals(Optional.of("1"), copy.get("ok"));
        assertEquals(1, copy.count());
    }

    /** The recovery handoff: the new log must be the old file cut to validBytes, so a commit made after a crash is found by the next recovery. */
    @Test
    void recoveryHandoffTruncatesToValidPrefix() {
        store.set("a", "1");
        int good = bytes.size();
        store.set("b", "2");
        byte[] torn = Arrays.copyOf(bytes.toByteArray(), bytes.size() - 2);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        InMemoryKvStore revived = InMemoryKvStore.recover(clock, torn, valid -> {
            file.write(torn, 0, valid);
            return new Wal(file);
        });
        assertEquals(good, file.size());
        revived.set("c", "3");
        InMemoryKvStore second = InMemoryKvStore.recover(clock, file.toByteArray(), NO_LOG);
        assertEquals(Optional.of("1"), second.get("a"));
        assertEquals(Optional.empty(), second.get("b"));
        assertEquals(Optional.of("3"), second.get("c"));
    }
}
