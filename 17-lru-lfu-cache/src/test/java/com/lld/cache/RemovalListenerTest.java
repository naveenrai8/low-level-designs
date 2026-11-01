package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The listener runs after unlock, can't break the cache, and gets the right cause. */
class RemovalListenerTest {

    /** Proves a listener that throws is reported to the error handler and does not break the put. */
    @Test
    void aThrowingListenerIsIsolatedAndReported() {
        RuntimeException boom = new IllegalStateException("listener bug");
        List<Removal<String, Integer>> reported = new ArrayList<>();
        List<RuntimeException> errors = new ArrayList<>();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().maximumSize(1)
                .removalListener((k, v, c) -> { throw boom; }, (r, e) -> { reported.add(r); errors.add(e); })
                .build();
        cache.put("a", 1);

        cache.put("b", 2);                              // evicts a; the listener throws; put still returns

        assertEquals(List.of(new Removal<>("a", 1, RemovalCause.SIZE)), reported);
        assertSame(boom, errors.get(0));
        assertEquals(Optional.of(2), cache.get("b"));
    }

    /** Same-thread probe: inside the callback, does this thread hold the cache lock? */
    @Test
    void theListenerRunsWithTheLockReleased() {
        AtomicReference<BoundedCache<String, Integer>> self = new AtomicReference<>();
        List<Boolean> heldDuringCallback = new ArrayList<>();
        BoundedCache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().maximumSize(1)
                .removalListener((k, v, c) -> heldDuringCallback.add(self.get().isLockHeldByCurrentThread()),
                        (r, e) -> { throw new AssertionError(e); })
                .buildBounded();
        self.set(cache);
        cache.put("a", 1);

        cache.put("b", 2);                              // SIZE
        cache.put("b", 3);                              // REPLACED
        cache.remove("b");                              // EXPLICIT

        assertEquals(List.of(false, false, false), heldDuringCallback);
    }

    /**
     * Why "outside the lock" matters. A same-thread call back into the cache would work either way, because ReentrantLock is reentrant, so it can't catch the bug. A listener that hands work to <em>another</em> thread and waits for it can: under the lock, that thread blocks forever. Here it must finish within a second.
     */
    @Test
    void aListenerThatWaitsOnAnotherThreadUsingTheCacheDoesNotDeadlock() throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Cache<String, Integer>> self = new AtomicReference<>();
            AtomicReference<Optional<Integer>> seenByOtherThread = new AtomicReference<>();
            List<RuntimeException> errors = new ArrayList<>();
            Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().maximumSize(1)
                    .removalListener((k, v, c) -> {
                        try {
                            seenByOtherThread.set(other.submit(() -> self.get().get("b")).get(1, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            throw new IllegalStateException("other thread blocked: " + e, e);
                        }
                    }, (r, e) -> errors.add(e))
                    .build();
            self.set(cache);
            cache.put("a", 1);

            cache.put("b", 2);

            assertEquals(List.of(), errors);
            assertEquals(Optional.of(2), seenByOtherThread.get());
        } finally {
            other.shutdownNow();
            assertTrue(other.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Proves REPLACED, EXPLICIT, SIZE and EXPIRED are each reported with the right key and value. */
    @Test
    void everyCauseIsReported() {
        FakeTicker ticker = new FakeTicker();
        RecordingListener<String, Integer> listener = new RecordingListener<>();
        Cache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder().maximumSize(2)
                .expireAfterWrite(Duration.ofSeconds(1)).ticker(ticker)
                .removalListener(listener, (r, e) -> { }).build();

        cache.put("a", 1);
        cache.put("a", 2);                              // REPLACED a=1
        cache.remove("a");                              // EXPLICIT a=2
        cache.put("b", 1);
        cache.put("c", 1);
        cache.put("d", 1);                              // SIZE b
        ticker.advance(Duration.ofSeconds(1));
        cache.get("c");                                 // EXPIRED c

        assertEquals(List.of(
                new Removal<>("a", 1, RemovalCause.REPLACED),
                new Removal<>("a", 2, RemovalCause.EXPLICIT),
                new Removal<>("b", 1, RemovalCause.SIZE),
                new Removal<>("c", 1, RemovalCause.EXPIRED)), listener.events);
    }

    /**
     * Isolation is per event. One insert evicts three entries and the listener throws on the first: the other two must still be delivered. A single try/catch around the whole loop would deliver one and drop two.
     */
    @Test
    void aThrowingListenerDoesNotCostTheOtherEventsOfTheSameOperation() {
        List<String> delivered = new ArrayList<>();
        List<Removal<String, String>> errors = new ArrayList<>();
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumWeight(10, (k, v) -> v.length())
                .removalListener((k, v, c) -> {
                    delivered.add(k);
                    if (delivered.size() == 1) {
                        throw new IllegalStateException("first event fails");
                    }
                }, (r, e) -> errors.add(r))
                .build();
        cache.put("a", "xxx");
        cache.put("b", "xxx");
        cache.put("c", "xxx");

        cache.put("d", "x".repeat(9));                  // evicts a, b and c in one operation

        assertEquals(List.of("a", "b", "c"), delivered);
        assertEquals(List.of(new Removal<>("a", "xxx", RemovalCause.SIZE)), errors);
    }

    /**
     * The error handler is caller code too. If it throws, the failure must not escape into
     * {@code put} (the cache already changed) or cost the remaining events.
     */
    @Test
    void aThrowingErrorHandlerIsContainedToo() {
        List<String> delivered = new ArrayList<>();
        List<String> handled = new ArrayList<>();
        Cache<String, String> cache = CacheBuilder.<String, String>newBuilder()
                .maximumWeight(10, (k, v) -> v.length())
                .removalListener((k, v, c) -> {
                    delivered.add(k);
                    throw new IllegalStateException("listener bug");
                }, (r, e) -> {
                    handled.add(r.key());
                    throw new IllegalStateException("handler bug");
                })
                .build();
        cache.put("a", "xxx");
        cache.put("b", "xxx");
        cache.put("c", "xxx");

        cache.put("d", "x".repeat(9));                  // returns normally

        assertEquals(List.of("a", "b", "c"), delivered);
        assertEquals(List.of("a", "b", "c"), handled);
        assertEquals(Optional.of("x".repeat(9)), cache.get("d"));
    }
}
