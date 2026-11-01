package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves that a copy-on-write list still allows duplicates when the code checks and then adds, and that {@code addIfAbsent} does not.
 */
class CopyOnWriteTest {

    /**
     * Proves that two threads registering the same URL both pass the {@code contains} check and the list ends up with the URL twice.
     */
    @Test
    void bugContainsThenAddOnACowListStillDuplicates() throws Exception {
        Registry registry = new CowCheckThenActRegistry(Barriers.meetIn(2));
        registerConcurrently(registry, "https://hooks.example/a");
        assertEquals(List.of("https://hooks.example/a", "https://hooks.example/a"), registry.all());
    }

    /**
     * Proves that over 200 rounds of two threads registering the same name, {@code addIfAbsent} stores each name exactly once.
     */
    @Test
    void fixAddIfAbsentIsAtomic() throws Exception {
        Registry registry = new CowAddIfAbsentRegistry();
        for (int round = 0; round < 200; round++) {
            registerConcurrently(registry, "url-" + round);
        }
        assertEquals(200, registry.all().size());
    }

    /**
     * Registers the same name from two threads at once and waits up to 10 seconds for both.
     */
    private static void registerConcurrently(Registry registry, String name) throws Exception {
        CompletableFuture<?> a = CompletableFuture.runAsync(() -> registry.register(name));
        CompletableFuture<?> b = CompletableFuture.runAsync(() -> registry.register(name));
        CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
    }
}
