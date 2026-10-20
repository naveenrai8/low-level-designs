package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CopyOnWriteTest {

    @Test
    void bugContainsThenAddOnACowListStillDuplicates() throws Exception {
        Registry registry = new CowCheckThenActRegistry(Barriers.meetIn(2));
        registerConcurrently(registry, "https://hooks.example/a");
        assertEquals(List.of("https://hooks.example/a", "https://hooks.example/a"), registry.all());
    }

    @Test
    void fixAddIfAbsentIsAtomic() throws Exception {
        Registry registry = new CowAddIfAbsentRegistry();
        for (int round = 0; round < 200; round++) {
            registerConcurrently(registry, "url-" + round);
        }
        assertEquals(200, registry.all().size());
    }

    private static void registerConcurrently(Registry registry, String name) throws Exception {
        CompletableFuture<?> a = CompletableFuture.runAsync(() -> registry.register(name));
        CompletableFuture<?> b = CompletableFuture.runAsync(() -> registry.register(name));
        CompletableFuture.allOf(a, b).get(10, TimeUnit.SECONDS);
    }
}
