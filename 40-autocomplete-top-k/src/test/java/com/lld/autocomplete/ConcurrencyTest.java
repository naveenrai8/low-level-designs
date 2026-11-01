package com.lld.autocomplete;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Proves readers never see a half-finished write and that concurrent writers lose nothing. No sleeps. */
class ConcurrencyTest {

    /** Runs {@code write} on this thread with a hook that, while the write lock is held, starts a reader thread running {@code read} and waits until the lock reports that reader as queued. Returns what the reader saw after the write finished. Fails if the reader got through the lock instead. */
    private static <T> T readerIsBlockedDuringWrite(TrieAutocomplete inner, java.util.function.Function<Autocomplete, T> read, java.util.function.Consumer<Autocomplete> write) throws Exception {
        AtomicReference<Thread> reader = new AtomicReference<>();
        AtomicReference<T> seen = new AtomicReference<>();
        AtomicBoolean blocked = new AtomicBoolean();
        AtomicBoolean armed = new AtomicBoolean();
        ConcurrentAutocomplete[] holder = new ConcurrentAutocomplete[1];
        holder[0] = new ConcurrentAutocomplete(inner, () -> {
            if (!armed.get()) {
                return;
            }
            Thread t = new Thread(() -> seen.set(read.apply(holder[0])));
            reader.set(t);
            t.start();
            // wait (no sleep) until the lock says the reader is queued, or the reader finished
            while (!holder[0].isQueued(t) && t.getState() != Thread.State.TERMINATED) {
                Thread.onSpinWait();
            }
            blocked.set(holder[0].isQueued(t));
        });
        armed.set(true);
        write.accept(holder[0]);
        reader.get().join();
        assertTrue(blocked.get(), "reader got through while the writer held the lock");
        return seen.get();
    }

    /** While a writer holds the lock (frozen inside its hook), a suggest reader must be queued on the lock, and once released it must see the whole update. */
    @Test
    void readerWaitsForTheWriterAndSeesTheFinishedUpdate() throws Exception {
        TrieAutocomplete inner = new TrieAutocomplete(3);
        inner.add("apple", 5);
        List<Suggestion> seen = readerIsBlockedDuringWrite(inner, c -> c.suggest("a", 3), c -> c.add("apply", 9));
        assertEquals(List.of("apply", "apple"), seen.stream().map(Suggestion::word).toList());
    }

    /** The same blocked-reader proof for each other read method: fuzzy suggest, count and size. */
    @Test
    void everyReadMethodWaitsForTheWriter() throws Exception {
        TrieAutocomplete inner = new TrieAutocomplete(3);
        inner.add("apple", 5);
        assertEquals("apply", readerIsBlockedDuringWrite(inner, c -> c.suggestFuzzy("apx", 3).get(0).word(), c -> c.add("apply", 9)));
        long now = readerIsBlockedDuringWrite(inner, c -> c.count("apply"), c -> c.add("apply", 1));
        assertEquals(10L, now);
        int size = readerIsBlockedDuringWrite(inner, Autocomplete::size, c -> c.add("zed", 1));
        assertEquals(3, size);
    }

    /** The writer side of remove: a reader started while remove holds the lock must wait and then see the word gone. */
    @Test
    void removeHoldsTheWriteLock() throws Exception {
        TrieAutocomplete inner = new TrieAutocomplete(3);
        inner.add("apple", 5);
        inner.add("apply", 2);
        List<Suggestion> seen = readerIsBlockedDuringWrite(inner, c -> c.suggest("a", 3), c -> c.remove("apple"));
        assertEquals(List.of("apply"), seen.stream().map(Suggestion::word).toList());
    }

    /** Several readers can hold the lock at the same time: two readers wait for each other inside the read, which would deadlock under an exclusive lock. */
    @Test
    void readersDoNotBlockEachOther() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        Autocomplete gated = new Spy(new TrieAutocomplete(3), prefix -> {
            bothInside.countDown();
            await(bothInside);
        });
        ConcurrentAutocomplete c = new ConcurrentAutocomplete(gated);
        Thread a = new Thread(() -> c.suggest("a", 1));
        Thread b = new Thread(() -> c.suggest("a", 1));
        a.start();
        b.start();
        a.join();
        b.join();
        assertEquals(0, bothInside.getCount());
    }

    /** Eight writers each add exactly 500 counts to every one of 20 words behind a start gate, while readers query: the totals are exact, every reader answer is sorted, and no thread threw. */
    @Test
    void concurrentWritersLoseNothing() throws Exception {
        ConcurrentAutocomplete c = new ConcurrentAutocomplete(new TrieAutocomplete(5));
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            words.add("w" + (char) ('a' + i));
        }
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean bad = new AtomicBoolean();
        List<Throwable> thrown = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            threads.add(guarded(start, thrown, () -> {
                for (int i = 0; i < 500; i++) {
                    for (String w : words) {
                        c.add(w, 1);
                    }
                }
            }));
        }
        for (int t = 0; t < 4; t++) {
            threads.add(guarded(start, thrown, () -> {
                for (int i = 0; i < 2000; i++) {
                    List<Suggestion> r = c.suggest("w", 5);
                    for (int j = 1; j < r.size(); j++) {
                        if (Suggestion.ORDER.compare(r.get(j - 1), r.get(j)) >= 0) {
                            bad.set(true);
                        }
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(List.of(), thrown);
        assertFalse(bad.get());
        for (String w : words) {
            assertEquals(4000, c.count(w));
        }
        assertEquals(20, c.size());
        assertTrue(c.suggest("w", 5).stream().allMatch(s -> s.count() == 4000));
    }

    /** Makes a thread that waits for the start gate, runs the body, and records anything it throws so the test can fail on it. */
    private static Thread guarded(CountDownLatch gate, List<Throwable> thrown, Runnable body) {
        return new Thread(() -> {
            await(gate);
            try {
                body.run();
            } catch (Throwable e) {
                thrown.add(e);
            }
        });
    }

    /** Blocks the calling thread until the gate opens. */
    private static void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
