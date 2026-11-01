package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Tests that garbage collection cost does not grow with the number of keys in the store. */
class GcCostTest {

    private final ManualClock clock = new ManualClock();
    private final InMemoryKvStore store = new InMemoryKvStore(clock);

    /** With 10,000 keys, 100 one-key transactions must visit far fewer than a full scan each (the old code visited about 1,000,000 keys). */
    @Test
    void singleKeyTransactionsDoNotScanAllKeys() {
        for (int i = 0; i < 10_000; i++) {
            store.set("k" + i, "v");
        }
        long before = store.gcVisits();
        Session s = store.openSession();
        for (int i = 0; i < 100; i++) {
            s.begin();
            s.set("k" + i, "w");
            s.commit();
        }
        long visits = store.gcVisits() - before;
        assertTrue(visits < 10_000, "visits=" + visits);
        assertEquals(10_000, store.versionCount());
    }

    /** When the oldest snapshot closes, old versions are still collected (the optimisation must not skip real work). */
    @Test
    void closingOldestSnapshotStillCollects() {
        store.set("k", "v1");
        Snapshot snap = store.openSnapshot();
        store.set("k", "v2");
        store.set("other", "x");
        store.set("other", "y", Duration.ofSeconds(5));
        assertEquals(4, store.versionCount());
        snap.close();
        assertEquals(2, store.versionCount());
    }
}
