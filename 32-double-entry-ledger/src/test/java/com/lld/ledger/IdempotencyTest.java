package com.lld.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.ledger.LedgerException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F2: the same key with the same entry posts once; the same key with a different entry is a conflict. */
class IdempotencyTest {

    private InMemoryLedger ledger;

    /** Opens a source with 1000 and a destination. */
    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger(new TestClock(), 3);
        ledger.openAccount(Account.of("a", AccountType.ASSET, "USD"));
        ledger.openAccount(Account.of("b", AccountType.LIABILITY, "USD"));
        ledger.openAccount(Account.overdraft("src", AccountType.EQUITY, "USD"));
        ledger.post(Draft.move("a", "src", 1_000, "seed"));
    }

    /** Ten replays of one key return the original entry and move the money once. */
    @Test
    void replaysPostOnce() {
        ledger.post(Draft.move("a", "b", 100, "fund b"));
        Draft pay = Draft.move("b", "a", 100, "pay");
        Entry first = ledger.post(pay, "k1");
        for (int i = 0; i < 10; i++) {
            assertSame(first, ledger.post(pay, "k1"));
        }
        assertEquals(0, ledger.naturalBalance("b"));
        assertEquals(1_000, ledger.naturalBalance("a"));
        assertEquals(3, ledger.journal().size());
    }

    /** The same key with a different entry is a conflict and posts nothing. */
    @Test
    void sameKeyDifferentEntryConflicts() {
        ledger.post(Draft.move("a", "b", 100, "one"), "k");
        int before = ledger.journal().size();
        assertEquals(Reason.IDEMPOTENCY_CONFLICT, assertThrows(LedgerException.class, () -> ledger.post(Draft.move("a", "b", 101, "one"), "k")).reason());
        assertEquals(before, ledger.journal().size());
    }

    /** A failed post (insufficient funds) is not remembered, so a retry after topping up succeeds under the same key. */
    @Test
    void failedPostCanBeRetried() {
        Draft pay = Draft.move("b", "a", 100, "pay");
        assertEquals(Reason.INSUFFICIENT_FUNDS, assertThrows(LedgerException.class, () -> ledger.post(pay, "k")).reason());
        ledger.post(Draft.move("a", "b", 100, "fund"));
        ledger.post(pay, "k");
        assertEquals(0, ledger.naturalBalance("b"));
    }

    /** A blank key is refused. */
    @Test
    void blankKeyRejected() {
        assertEquals(Reason.BAD_ARGUMENT, assertThrows(LedgerException.class, () -> ledger.post(Draft.move("a", "b", 1, "x"), " ")).reason());
    }

    /** Memory is bounded: with room for 3 keys, the oldest key is forgotten after 4 distinct keys (documented trade-off). */
    @Test
    void keyMemoryIsBounded() {
        Entry first = ledger.post(Draft.move("a", "b", 1, "m"), "k0");
        for (int i = 1; i <= 3; i++) {
            ledger.post(Draft.move("a", "b", 1, "m"), "k" + i);
        }
        for (int k = 1; k <= 3; k++) {
            int size = ledger.journal().size();
            ledger.post(Draft.move("a", "b", 1, "m"), "k" + k);
            assertEquals(size, ledger.journal().size(), "k" + k + " must still replay");
        }
        Entry again = ledger.post(Draft.move("a", "b", 1, "m"), "k0");
        assertNotEquals(first.seq(), again.seq());
        assertEquals(ledger.journal().size(), again.seq());
    }
}
