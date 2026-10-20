package com.learning.lrucache.controller;

import com.learning.lrucache.core.LRUCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/cache")
public class LRUCacheController {
    private final LRUCache<String, String> cache;

    public LRUCacheController() {
        this.cache = new LRUCache<>(100); // Default capacity 100
    }

    @GetMapping("/{key}")
    public ResponseEntity<?> get(@PathVariable String key) {
        String value = cache.get(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @PostMapping
    public ResponseEntity<?> put(@RequestParam String key, @RequestParam String value) {
        cache.put(key, value);
        return ResponseEntity.ok(Map.of("message", "Cached successfully", "key", key));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(Map.of(
            "size", cache.size(),
            "capacity", cache.capacity()
        ));
    }

    @DeleteMapping
    public ResponseEntity<?> clear() {
        cache.clear();
        return ResponseEntity.ok(Map.of("message", "Cache cleared"));
    }
}
