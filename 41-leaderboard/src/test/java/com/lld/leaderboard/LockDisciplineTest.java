package com.lld.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;

/** Checks that every wrapper method passes its arguments and result through unchanged, and holds the right lock while it does. */
class LockDisciplineTest {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<String> log = new ArrayList<>();
    private Runnable inside = () -> { };

    /** A delegate that returns a different marker value from every method, and records the lock state it sees on each call. */
    private final Leaderboard spy = new Leaderboard() {
        /** Records "name:W" if the calling thread holds the write lock, "name:R" if it holds a read lock only, else "name:-". */
        private void see(String name) {
            log.add(name + (lock.isWriteLockedByCurrentThread() ? ":W" : lock.getReadLockCount() > 0 ? ":R" : ":-"));
            inside.run();
        }

        /** Records the lock state seen by setScore and returns a marker value. */
        @Override
        public void setScore(String user, long score) {
            see("setScore " + user + " " + score);
        }

        /** Records the lock state seen by increment and returns a marker value. */
        @Override
        public long increment(String user, long delta) {
            see("increment " + user + " " + delta);
            return 101;
        }

        /** Records the lock state seen by remove and returns a marker value. */
        @Override
        public boolean remove(String user) {
            see("remove " + user);
            return true;
        }

        /** Records the lock state seen by score and returns a marker value. */
        @Override
        public OptionalLong score(String user) {
            see("score " + user);
            return OptionalLong.of(102);
        }

        /** Records the lock state seen by rank and returns a marker value. */
        @Override
        public OptionalInt rank(String user) {
            see("rank " + user);
            return OptionalInt.of(103);
        }

        /** Records the lock state seen by competitionRank and returns a marker value. */
        @Override
        public OptionalInt competitionRank(String user) {
            see("competitionRank " + user);
            return OptionalInt.of(104);
        }

        /** Records the lock state seen by top and returns a marker value. */
        @Override
        public List<Ranked> top(int k) {
            see("top " + k);
            return List.of(new Ranked(1, "t", 105, 0));
        }

        /** Records the lock state seen by range and returns a marker value. */
        @Override
        public List<Ranked> range(int fromRank, int toRank) {
            see("range " + fromRank + " " + toRank);
            return List.of(new Ranked(2, "r", 106, 0));
        }

        /** Records the lock state seen by size and returns a marker value. */
        @Override
        public int size() {
            see("size");
            return 107;
        }
    };

    private final ConcurrentLeaderboard board = new ConcurrentLeaderboard(spy, lock);

    /** Proves each write method forwards its exact arguments and result, and runs under the write lock. */
    @Test
    void writesForwardExactlyAndHoldTheWriteLock() {
        board.setScore("u", 7);
        assertEquals(101, board.increment("u", 3));
        assertTrue(board.remove("u"));
        assertEquals(List.of("setScore u 7:W", "increment u 3:W", "remove u:W"), log);
    }

    /** Proves each read method forwards its exact arguments and result, and runs under a read lock (never the write lock, never no lock). */
    @Test
    void readsForwardExactlyAndHoldTheReadLock() {
        assertEquals(OptionalLong.of(102), board.score("u"));
        assertEquals(OptionalInt.of(103), board.rank("u"));
        assertEquals(OptionalInt.of(104), board.competitionRank("u"));
        assertEquals("t", board.top(4).get(0).user());
        assertEquals("r", board.range(2, 5).get(0).user());
        assertEquals(107, board.size());
        assertEquals(List.of("score u:R", "rank u:R", "competitionRank u:R", "top 4:R", "range 2 5:R", "size:R"), log);
    }

    /** Proves locks are released after every call, even when the delegate throws. */
    @Test
    void locksAreReleasedEvenOnException() {
        inside = () -> {
            throw new IllegalStateException("boom");
        };
        try {
            board.increment("u", 1);
        } catch (IllegalStateException expected) {
            // expected
        }
        try {
            board.rank("u");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertFalse(lock.isWriteLocked());
        assertEquals(0, lock.getReadLockCount());
    }

    /** Proves a reader really waits for a writer: a writer is frozen mid-update by a latch, a reader thread calls rank, and the lock reports that reader queued. Only after the writer is released does the reader finish. */
    @Test
    void readerBlocksWhileAWriterIsMidUpdate() throws Exception {
        CountDownLatch inWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        inside = () -> {
            if (lock.isWriteLockedByCurrentThread()) {
                inWrite.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Thread writer = new Thread(() -> board.setScore("u", 1));
        writer.start();
        assertTrue(inWrite.await(5, TimeUnit.SECONDS));
        Thread reader = new Thread(() -> board.rank("u"));
        reader.start();
        while (!lock.hasQueuedThread(reader)) {
            Thread.yield();
        }
        assertTrue(reader.isAlive());
        release.countDown();
        writer.join();
        reader.join();
    }
}
