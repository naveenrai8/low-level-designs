package com.lld.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests nested transactions: rollback restores values and TTLs, inner commit merges up, errors without a transaction. */
class TransactionTest {

    private final ManualClock clock = new ManualClock();
    private final InMemoryKvStore store = new InMemoryKvStore(clock);
    private final Session s = store.openSession();

    /** Rolling back an inner transaction that changed a TTL gives back the outer value and the outer expiry. */
    @Test
    void innerRollbackRestoresOuterTtl() {
        s.begin();
        s.set("k", "outer", Duration.ofSeconds(10));
        s.begin();
        s.expire("k", Duration.ofSeconds(100));
        s.rollback();
        assertEquals(Optional.of(Duration.ofSeconds(10)), s.ttl("k"));
        s.commit();
        clock.advance(Duration.ofSeconds(10));
        assertFalse(store.exists("k"));
    }

    /** Rolling back an inner delete brings back both the outer value and the outer expiry. */
    @Test
    void innerRollbackOfDeleteRestoresValueAndTtl() {
        s.begin();
        s.set("k", "outer", Duration.ofSeconds(10));
        s.begin();
        assertTrue(s.delete("k"));
        assertFalse(s.exists("k"));
        s.rollback();
        assertEquals(Optional.of("outer"), s.get("k"));
        assertEquals(Optional.of(Duration.ofSeconds(10)), s.ttl("k"));
        s.commit();
        assertEquals(Optional.of("outer"), store.get("k"));
    }

    /** Rolling back an inner set-without-TTL puts the outer TTL back (the cleared TTL is not leaked). */
    @Test
    void innerRollbackOfPlainSetRestoresTtl() {
        s.begin();
        s.set("k", "a", Duration.ofSeconds(10));
        s.begin();
        s.set("k", "b");
        assertEquals(Optional.empty(), s.ttl("k"));
        s.rollback();
        assertEquals(Optional.of(Duration.ofSeconds(10)), s.ttl("k"));
        s.rollback();
    }

    /** A key that did not exist before the transaction is fully gone after rollback: no value, no entry, no memory. */
    @Test
    void rollbackOfNewKeyLeavesNothing() {
        s.begin();
        s.set("fresh", "v");
        assertEquals(1, s.count());
        s.rollback();
        assertEquals(Optional.empty(), s.get("fresh"));
        assertEquals(0, store.count());
        assertEquals(0, store.physicalSize());
        assertEquals(0, store.versionCount());
    }

    /** Rolling back a delete of a committed key keeps the committed key. */
    @Test
    void rollbackKeepsCommittedKey() {
        store.set("k", "v", Duration.ofSeconds(5));
        s.begin();
        s.delete("k");
        s.rollback();
        assertEquals(0, s.depth());
        assertEquals(Optional.of("v"), s.get("k"));
        assertEquals(Optional.of("v"), store.get("k"));
        assertEquals(Optional.of(Duration.ofSeconds(5)), store.ttl("k"));
    }

    /** An inner commit merges into its parent only: other readers see nothing until the outermost commit. */
    @Test
    void innerCommitMergesIntoParentNotStore() {
        s.begin();
        s.begin();
        s.set("k", "v");
        s.commit();
        assertEquals(Optional.of("v"), s.get("k"));
        assertEquals(Optional.empty(), store.get("k"));
        assertEquals(1, s.depth());
        s.commit();
        assertEquals(Optional.of("v"), store.get("k"));
    }

    /** After an inner commit, rolling back the outer transaction discards the merged changes too. */
    @Test
    void outerRollbackDiscardsMergedInnerCommit() {
        s.begin();
        s.begin();
        s.set("k", "v");
        s.commit();
        s.rollback();
        assertEquals(0, s.depth());
        assertEquals(Optional.empty(), s.get("k"));
        assertEquals(Optional.empty(), store.get("k"));
        assertEquals(0, store.physicalSize());
    }

    /** Commit and rollback with no open transaction throw NoTransactionException. */
    @Test
    void commitAndRollbackWithoutTransactionThrow() {
        assertThrows(NoTransactionException.class, s::commit);
        assertThrows(NoTransactionException.class, s::rollback);
        s.begin();
        s.commit();
        assertThrows(NoTransactionException.class, s::commit);
    }

    /** Without a transaction, a session writes straight through (auto-commit). */
    @Test
    void autoCommitWithoutTransaction() {
        s.set("k", "v");
        assertEquals(Optional.of("v"), store.get("k"));
    }

    /** Inside a transaction a key can expire; the deadline is judged by the live clock and the key vanishes from the transaction's view. */
    @Test
    void keyExpiresInsideTransaction() {
        store.set("k", "v", Duration.ofSeconds(5));
        s.begin();
        assertTrue(s.exists("k"));
        clock.advance(Duration.ofSeconds(5));
        assertFalse(s.exists("k"));
        assertEquals(0, s.count());
        s.rollback();
    }

    /** Repeatable read: a transaction does not see another writer's commit made after it began. */
    @Test
    void transactionReadsItsBeginState() {
        store.set("k", "old");
        s.begin();
        store.set("k", "new");
        assertEquals(Optional.of("old"), s.get("k"));
        s.rollback();
        assertEquals(Optional.of("new"), s.get("k"));
    }

    /** Closing a session rolls back every open level and releases its pinned view, so old versions can be collected. */
    @Test
    void closeRollsBackAllLevelsAndReleasesPin() {
        store.set("k", "v1");
        try (Session scoped = store.openSession()) {
            scoped.begin();
            scoped.begin();
            scoped.set("k", "inner");
            store.set("k", "v2");
            assertEquals(2, store.versionCount());
            scoped.close();
            assertEquals(0, scoped.depth());
        }
        assertEquals(1, store.versionCount());
        assertEquals(Optional.of("v2"), store.get("k"));
    }

    /** Pins the write-skew limit: two transactions read the same key, write different keys, and both commit (snapshot isolation is not serializable). */
    @Test
    void writeSkewIsAllowed() {
        store.set("a", "1");
        Session s1 = store.openSession();
        Session s2 = store.openSession();
        s1.begin();
        s2.begin();
        assertEquals(Optional.of("1"), s1.get("a"));
        assertEquals(Optional.of("1"), s2.get("a"));
        s1.set("b", "from-1");
        s2.set("c", "from-2");
        s1.commit();
        s2.commit();
        assertEquals(3, store.count());
    }

    /** Pins a known limit: a TTL set inside a transaction counts from the call, not from commit, so a slow commit can install an already-dead key (Redis starts SET EX at EXEC instead). */
    @Test
    void ttlInsideTransactionCountsFromTheCall() {
        s.begin();
        s.set("k", "v", Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(6));
        s.commit();
        assertEquals(Optional.empty(), store.get("k"));
        assertEquals(0, store.count());
    }
}
