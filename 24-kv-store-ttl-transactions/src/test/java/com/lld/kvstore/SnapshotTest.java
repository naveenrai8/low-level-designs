package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests snapshot reads: isolation from later writes, the expiry rule, and version garbage collection. */
class SnapshotTest {

    private final ManualClock clock = new ManualClock();
    private final InMemoryKvStore store = new InMemoryKvStore(clock);

    /** A snapshot never sees sets, deletes or new keys written after it began. */
    @Test
    void snapshotIgnoresLaterWrites() {
        store.set("a", "1");
        store.set("b", "1");
        try (Snapshot snap = store.openSnapshot()) {
            store.set("a", "2");
            store.delete("b");
            store.set("c", "1");
            assertEquals(Optional.of("1"), snap.get("a"));
            assertEquals(Optional.of("1"), snap.get("b"));
            assertFalse(snap.exists("c"));
            assertEquals(2, snap.count());
            assertEquals(2, store.count());
        }
    }

    /** Rule: a snapshot judges expiry at snapshot time, so a key alive then stays visible after it expires and is swept. */
    @Test
    void keyAliveAtSnapshotStaysVisible() {
        store.set("k", "v", Duration.ofSeconds(5));
        try (Snapshot snap = store.openSnapshot()) {
            clock.advance(Duration.ofSeconds(10));
            assertEquals(1, store.sweep(10));
            assertFalse(store.exists("k"));
            assertEquals(Optional.of("v"), snap.get("k"));
            assertEquals(1, snap.count());
        }
    }

    /** A key already expired at snapshot time stays invisible, and a key set later is not seen. */
    @Test
    void keyExpiredAtSnapshotStaysInvisible() {
        store.set("k", "v", Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(5));
        try (Snapshot snap = store.openSnapshot()) {
            assertFalse(snap.exists("k"));
            store.set("k", "again");
            assertFalse(snap.exists("k"));
        }
    }

    /** Old versions are kept while a snapshot needs them and dropped when it closes. */
    @Test
    void oldVersionsAreGarbageCollected() {
        store.set("k", "v1");
        Snapshot snap = store.openSnapshot();
        store.set("k", "v2");
        store.set("k", "v3");
        assertEquals(3, store.versionCount());
        assertEquals(Optional.of("v1"), snap.get("k"));
        snap.close();
        snap.close();
        assertEquals(1, store.versionCount());
        assertEquals(Optional.of("v3"), store.get("k"));
    }

    /** With two snapshots, versions are dropped only once the older one closes. */
    @Test
    void gcWaitsForOldestSnapshot() {
        store.set("k", "v1");
        Snapshot first = store.openSnapshot();
        store.set("k", "v2");
        Snapshot second = store.openSnapshot();
        store.set("k", "v3");
        assertEquals(3, store.versionCount());
        second.close();
        assertEquals(3, store.versionCount());
        first.close();
        assertEquals(1, store.versionCount());
    }

    /** A deleted key leaves no tombstone behind once nobody needs it. */
    @Test
    void tombstonesAreCollected() {
        store.set("k", "v");
        Snapshot snap = store.openSnapshot();
        store.delete("k");
        assertEquals(2, store.versionCount());
        snap.close();
        assertEquals(0, store.versionCount());
        assertEquals(0, store.physicalSize());
    }

    /** Using a closed snapshot is an error, because its versions may be gone. */
    @Test
    void closedSnapshotThrows() {
        Snapshot snap = store.openSnapshot();
        snap.close();
        assertThrows(IllegalStateException.class, () -> snap.get("k"));
    }

    /** Closing a snapshot twice must not release another snapshot's pin: the second snapshot still reads its old value. */
    @Test
    void doubleCloseKeepsOtherSnapshotsPin() {
        store.set("k", "v1");
        Snapshot s1 = store.openSnapshot();
        Snapshot s2 = store.openSnapshot();
        store.set("k", "v2");
        s1.close();
        s1.close();
        assertEquals(Optional.of("v1"), s2.get("k"));
        s2.close();
        assertEquals(1, store.versionCount());
    }

    /** A sweep while a snapshot is open hides the key from the store at once, but its memory is only freed when the snapshot closes. */
    @Test
    void sweepUnderSnapshotFreesMemoryOnlyAfterClose() {
        store.set("k", "v", Duration.ofSeconds(1));
        Snapshot snap = store.openSnapshot();
        clock.advance(Duration.ofSeconds(2));
        assertEquals(1, store.sweep(10));
        assertEquals(1, store.physicalSize());
        snap.close();
        assertEquals(0, store.physicalSize());
    }
}
