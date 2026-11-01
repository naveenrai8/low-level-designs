package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Compares the real store and session with a naive model (a plain map plus a stack of full copies) over seeded random operations. */
class DifferentialTest {

    /** The naive reference: value+deadline per key, and begin pushes a full copy that rollback restores. */
    private static final class Model {
        private Map<String, long[]> data = new HashMap<>();
        private final Map<String, String> values = new HashMap<>();
        private final Deque<Map<String, long[]>> savedData = new ArrayDeque<>();
        private final Deque<Map<String, String>> savedValues = new ArrayDeque<>();

        /** Tells whether the key is alive at the given time. */
        boolean live(String k, long now) {
            long[] d = data.get(k);
            return d != null && now < d[0];
        }

        /** Sets a key; deadline Long.MAX_VALUE means no expiry. */
        void set(String k, String v, long deadline) {
            data.put(k, new long[] {deadline});
            values.put(k, v);
        }

        /** Removes a key. */
        void del(String k) {
            data.remove(k);
            values.remove(k);
        }

        /** Pushes a full copy of the state. */
        void begin() {
            Map<String, long[]> copy = new HashMap<>();
            data.forEach((k, v) -> copy.put(k, v.clone()));
            savedData.push(copy);
            savedValues.push(new HashMap<>(values));
        }

        /** Drops the saved copy, keeping the current state. */
        void commit() {
            savedData.pop();
            savedValues.pop();
        }

        /** Restores the saved copy. */
        void rollback() {
            data = savedData.pop();
            values.clear();
            values.putAll(savedValues.pop());
        }
    }

    /** Runs 20 seeds of 3000 random operations and checks every read against the model. */
    @Test
    void matchesNaiveModel() {
        for (long seed = 1; seed <= 20; seed++) {
            run(seed);
        }
    }

    /** Runs one seeded random sequence and fails with the seed and step on the first difference. */
    private void run(long seed) {
        Random rnd = new Random(seed);
        ManualClock clock = new ManualClock();
        InMemoryKvStore store = new InMemoryKvStore(clock);
        Session s = store.openSession();
        Model m = new Model();
        int depth = 0;
        Session other = store.openSession();
        Snapshot snap = null;
        Map<String, String> frozenValues = null;
        long frozenAt = 0;
        for (int step = 0; step < 3000; step++) {
            String ctx = "seed " + seed + " step " + step;
            String k = "k" + rnd.nextInt(6);
            long now = clock.millis();
            int op = rnd.nextInt(12);
            switch (op) {
                case 0, 1 -> {
                    String v = "v" + rnd.nextInt(100);
                    s.set(k, v);
                    m.set(k, v, Long.MAX_VALUE);
                }
                case 2, 3 -> {
                    String v = "v" + rnd.nextInt(100);
                    int ms = 1 + rnd.nextInt(50);
                    s.set(k, v, Duration.ofMillis(ms));
                    m.set(k, v, now + ms);
                }
                case 4 -> {
                    assertEquals(m.live(k, now), s.delete(k), ctx);
                    m.del(k);
                }
                case 5 -> {
                    int ms = 1 + rnd.nextInt(50);
                    boolean ok = s.expire(k, Duration.ofMillis(ms));
                    assertEquals(m.live(k, now), ok, ctx);
                    if (ok) {
                        m.set(k, m.values.get(k), now + ms);
                    }
                }
                case 6 -> {
                    clock.advance(Duration.ofMillis(rnd.nextInt(30)));
                    store.sweep(rnd.nextInt(4));
                }
                case 7 -> {
                    if (depth < 4) {
                        s.begin();
                        m.begin();
                        depth++;
                    }
                }
                case 8 -> {
                    if (depth > 0) {
                        s.commit();
                        m.commit();
                        depth--;
                    }
                }
                case 9 -> {
                    if (depth > 0) {
                        s.rollback();
                        m.rollback();
                        depth--;
                    }
                }
                default -> {
                    // pure read, checked below
                }
            }
            for (int i = 0; i < 6; i++) {
                String key = "k" + i;
                long t = clock.millis();
                Optional<String> expected = m.live(key, t) ? Optional.of(m.values.get(key)) : Optional.empty();
                assertEquals(expected, s.get(key), ctx + " get " + key);
                Optional<Duration> expectedTtl = m.live(key, t) && m.data.get(key)[0] != Long.MAX_VALUE
                        ? Optional.of(Duration.ofMillis(m.data.get(key)[0] - t)) : Optional.empty();
                assertEquals(expectedTtl, s.ttl(key), ctx + " ttl " + key);
            }
            if (rnd.nextInt(10) == 0) {
                if (snap != null) {
                    checkSnapshot(snap, frozenValues, frozenAt, ctx);
                    snap.close();
                    snap = null;
                } else if (depth == 0) {
                    snap = store.openSnapshot();
                    frozenAt = clock.millis();
                    frozenValues = new HashMap<>();
                    for (int i = 0; i < 6; i++) {
                        if (m.live("k" + i, frozenAt)) {
                            frozenValues.put("k" + i, m.values.get("k" + i));
                        }
                    }
                }
            }
            if (depth == 0 && rnd.nextInt(8) == 0) {
                String ok = "k" + rnd.nextInt(6);
                other.set(ok, "o" + rnd.nextInt(100));
                m.set(ok, store.get(ok).orElseThrow(), Long.MAX_VALUE);
            }
            if (depth == 0 && snap == null) {
                assertEquals(store.physicalSize(), store.versionCount(), ctx + " versions");
            }
            long live = m.data.keySet().stream().filter(x -> m.live(x, clock.millis())).count();
            assertEquals(live, s.count(), ctx + " count");
        }
        while (depth-- > 0) {
            s.rollback();
        }
        if (snap != null) {
            snap.close();
        }
        assertEquals(store.physicalSize(), store.versionCount(), "seed " + seed + " leftover versions");
    }

    /** Checks a snapshot still shows exactly the frozen model copy, even though the store has moved on. */
    private void checkSnapshot(Snapshot snap, Map<String, String> frozen, long at, String ctx) {
        for (int i = 0; i < 6; i++) {
            String key = "k" + i;
            assertEquals(Optional.ofNullable(frozen.get(key)), snap.get(key), ctx + " snapshot " + key);
        }
        assertEquals(frozen.size(), snap.count(), ctx + " snapshot count at " + at);
    }
}
