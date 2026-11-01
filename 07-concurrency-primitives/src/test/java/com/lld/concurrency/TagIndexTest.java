package com.lld.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves the ArrayList-backed tag index corrupts under concurrent writes and the concurrent-set index does not.
 */
class TagIndexTest {

    private static final int WRITERS = 8;
    private static final int ITEMS_PER_WRITER = 20_000;
    private static final int EXPECTED = WRITERS * ITEMS_PER_WRITER;

    /**
     * Proves that concurrent adds to a plain ArrayList lose items or throw.
     * <p>The race is inside {@code ArrayList.add}, so the test is probabilistic and retries until the bug shows.
     */
    @Test
    void bugArrayListValuesLoseItemsOrThrow() throws Exception {
        String observed = null;
        for (int attempt = 0; attempt < 10 && observed == null; attempt++) {
            TagIndex index = new ArrayListTagIndex();
            Throwable failure = writeConcurrently(index, false);
            if (failure != null) {
                observed = "a writer threw " + failure;
                continue;
            }
            try {
                int size = index.itemsTagged("hot").size();
                if (size != EXPECTED) {
                    observed = "lost " + (EXPECTED - size) + " items";
                }
            } catch (RuntimeException e) {
                observed = "the list is corrupt: " + e; // racing add() can leave null slots
            }
        }
        assertTrue(observed != null, "expected lost items or an exception");
    }

    /**
     * Proves that with a concurrent set every item is kept, and a reader running alongside the writers never throws.
     */
    @Test
    void fixConcurrentSetKeepsEveryItemAndReadersNeverThrow() throws Exception {
        TagIndex index = new ConcurrentSetTagIndex();

        Throwable failure = writeConcurrently(index, true);

        assertNull(failure);
        assertEquals(EXPECTED, index.itemsTagged("hot").size());
    }

    /**
     * Starts WRITERS threads that add distinct items to one tag, and optionally one extra thread that reads the tag the whole time. Returns the first exception any thread threw, or null.
     */
    private static Throwable writeConcurrently(TagIndex index, boolean withReader) throws Exception {
        AtomicBoolean writing = new AtomicBoolean(true);
        int threads = withReader ? WRITERS + 1 : WRITERS;
        AtomicInteger writersLeft = new AtomicInteger(WRITERS);
        return Race.run(threads, t -> () -> {
            if (t == WRITERS) {
                while (writing.get()) {
                    index.itemsTagged("hot"); // would throw ConcurrentModificationException on ArrayList
                }
                return;
            }
            for (int i = 0; i < ITEMS_PER_WRITER; i++) {
                index.tag("hot", t + ":" + i);
            }
            if (writersLeft.decrementAndGet() == 0) {
                writing.set(false);
            }
        });
    }
}
