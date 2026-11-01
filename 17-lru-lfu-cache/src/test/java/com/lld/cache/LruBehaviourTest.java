package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What makes LRU LRU: recency of <em>use</em>, not of insertion. */
class LruBehaviourTest {

    private final RecordingListener<String, Integer> listener = new RecordingListener<>();
    private final BoundedCache<String, Integer> cache = CacheBuilder.<String, Integer>newBuilder()
            .policy(Policy.LRU).maximumSize(3).removalListener(listener, (r, e) -> { }).buildBounded();

    /** Proves a get makes the key the most recent, so another key is evicted instead. */
    @Test
    void getRefreshesRecency() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a");                                // a was oldest; now b is

        cache.put("d", 4);

        assertEquals(List.of("b"), listener.keys(RemovalCause.SIZE));
        assertEquals(Set.of("a", "c", "d"), cache.keySnapshot());
    }

    /** Proves overwriting a key also makes it the most recent. */
    @Test
    void putUpdateRefreshesRecency() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.put("a", 10);

        cache.put("d", 4);

        assertEquals(List.of("b"), listener.keys(RemovalCause.SIZE));
    }

    /** Proves victim skips only the excluded key and returns null when that is the only key. */
    @Test
    void victimSkipsTheExcludedKeyAndNothingElse() {
        LruPolicy<String> policy = new LruPolicy<>();
        assertNull(policy.victim(null));
        policy.onInsert("a");
        assertNull(policy.victim("a"));                // the only key is protected: nothing to evict
        policy.onInsert("b");

        assertEquals("a", policy.victim(null));
        assertEquals("b", policy.victim("a"));
    }
}
