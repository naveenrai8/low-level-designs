package com.lld.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Model-based test: 100,000 seeded random operations, and after <b>every</b> one the O(1) cache must agree with a naive O(n) model on the returned value and on the exact key set. A small key space (32) over a small capacity (8) keeps the cache full, so most puts evict. A failure prints the seed and the step, and the run replays exactly.
 */
class DifferentialTest {

    static final int OPS = 100_000;
    static final int KEYS = 32;
    static final int CAPACITY = 8;

    /** Supplies every policy paired with five fixed seeds. */
    static Stream<Arguments> policiesAndSeeds() {
        return Stream.of(Policy.values())
                .flatMap(p -> Stream.of(1L, 2L, 3L, 42L, 2026L).map(seed -> Arguments.of(p, seed)));
    }

    /** Proves the real cache matches the naive model on every step for the given policy and seed. */
    @ParameterizedTest(name = "{0} seed={1}")
    @MethodSource("policiesAndSeeds")
    void agreesWithTheNaiveModelAfterEveryOperation(Policy policy, long seed) {
        BoundedCache<Integer, Integer> cache = CacheBuilder.<Integer, Integer>newBuilder()
                .policy(policy).maximumSize(CAPACITY).ticker(new FakeTicker()).buildBounded();
        ReferenceModels.Model model = policy == Policy.LRU
                ? new ReferenceModels.Lru(CAPACITY) : new ReferenceModels.Lfu(CAPACITY);

        run(cache, model, seed);
    }

    /** Shared with {@link MinFreqCounterLfuTest}, which runs a different policy through the same oracle. */
    static void run(BoundedCache<Integer, Integer> cache, ReferenceModels.Model model, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        for (int step = 0; step < OPS; step++) {
            int key = random.nextInt(KEYS);
            int op = random.nextInt(10);
            String where = "seed=" + seed + " step=" + step + " key=" + key;
            if (op < 5) {
                assertEquals(model.get(key), cache.get(key).orElse(null), where + " get");
            } else if (op < 9) {
                cache.put(key, step);
                model.put(key, step);
            } else {
                assertEquals(model.remove(key), cache.remove(key).orElse(null), where + " remove");
            }
            assertEquals(model.keys(), cache.keySnapshot(), where + " keys");
            cache.checkInvariants();
        }
    }
}
