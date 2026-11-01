package com.lld.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** F3: the running balances equal a recomputation from the journal, and any past balance can be read. */
class ProjectionTest {

    /** Builds a ledger with three accounts and an overdraft source. */
    private static InMemoryLedger ledger() {
        InMemoryLedger l = new InMemoryLedger(new TestClock());
        l.openAccount(Account.of("a", AccountType.ASSET, "USD"));
        l.openAccount(Account.of("b", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.overdraft("c", AccountType.EQUITY, "USD"));
        return l;
    }

    /** Differential test: after 3000 random entries (some reversed) the projection equals rebuild() for every account. */
    @Test
    void projectionEqualsRebuild() {
        InMemoryLedger l = ledger();
        Random rnd = new Random(7);
        String[] ids = {"a", "b", "c"};
        for (int i = 0; i < 3_000; i++) {
            try {
                if (i % 7 == 6) {
                    l.reverse(l.journal().get(rnd.nextInt(l.journal().size())).id());
                } else {
                    l.post(Draft.move(ids[rnd.nextInt(3)], ids[rnd.nextInt(3)], 1 + rnd.nextInt(50), "r"));
                }
            } catch (LedgerException e) {
                // refused entries leave no trace, which is exactly what the comparison below checks
            }
        }
        Map<String, Long> rebuilt = l.rebuild();
        for (String id : ids) {
            assertEquals(rebuilt.get(id), l.balance(id), id);
        }
    }

    /** balanceAt gives the balance just after each entry, matching a brute-force replay of the journal prefix. */
    @Test
    void balanceAtMatchesReplay() {
        InMemoryLedger l = ledger();
        Random rnd = new Random(11);
        for (int i = 0; i < 400; i++) {
            String from = rnd.nextBoolean() ? "c" : "a";
            l.post(Draft.move(from, "b".equals(from) ? "a" : "b", 1 + rnd.nextInt(20), "r"));
        }
        for (String id : new String[] {"a", "b", "c"}) {
            long running = 0;
            int next = 0;
            for (long seq = 0; seq <= l.journal().size() + 3; seq++) {
                while (next < l.journal().size() && l.journal().get(next).seq() <= seq) {
                    for (Posting p : l.journal().get(next).postings()) {
                        if (p.accountId().equals(id)) {
                            running += p.amount();
                        }
                    }
                    next++;
                }
                assertEquals(running, l.balanceAt(id, seq), id + "@" + seq);
            }
        }
    }

    /** A small hand example: the balance before the first entry is 0 and each entry moves it as expected. */
    @Test
    void balanceAtHandExample() {
        InMemoryLedger l = ledger();
        l.post(Draft.move("a", "b", 100, "1"));
        l.post(Draft.move("a", "b", 50, "2"));
        l.post(Draft.move("c", "a", 10, "3"));
        assertEquals(0, l.balanceAt("a", 0));
        assertEquals(100, l.balanceAt("a", 1));
        assertEquals(150, l.balanceAt("a", 2));
        assertEquals(140, l.balanceAt("a", 3));
        assertEquals(150, l.balanceAt("a", 2));
        assertEquals(140, l.balanceAt("a", 99));
    }
}
