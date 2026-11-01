package com.lld.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.ledger.LedgerException.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Property-style test: after any sequence of operations the books still balance. */
class InvariantTest {

    /** Builds a ledger with several USD and EUR accounts, some of which may not go below zero. */
    private static InMemoryLedger world(List<String> usd, List<String> eur) {
        InMemoryLedger l = new InMemoryLedger(new TestClock());
        for (int i = 0; i < 5; i++) {
            String id = "u" + i;
            usd.add(id);
            l.openAccount(i < 3 ? Account.overdraft(id, AccountType.values()[i], "USD") : Account.of(id, AccountType.values()[i], "USD"));
        }
        for (int i = 0; i < 3; i++) {
            String id = "e" + i;
            eur.add(id);
            l.openAccount(i == 0 ? Account.overdraft(id, AccountType.EQUITY, "EUR") : Account.of(id, AccountType.ASSET, "EUR"));
        }
        return l;
    }

    /** Makes a random balanced draft over 2 to 4 accounts of one list; the last line is minus the sum of the others. */
    private static Draft randomDraft(Random rnd, List<String> ids) {
        int n = 2 + rnd.nextInt(3);
        List<Posting> ps = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < n - 1; i++) {
            long a = rnd.nextInt(2_001) - 1_000;
            sum += a;
            ps.add(new Posting(ids.get(rnd.nextInt(ids.size())), a));
        }
        ps.add(new Posting(ids.get(rnd.nextInt(ids.size())), -sum));
        return new Draft(ps, "random");
    }

    /** Over 6000 random operations (posts, some refused, some reversals) the sum of balances stays 0 per currency and the projection equals a rebuild. */
    @Test
    void booksAlwaysBalance() {
        Random rnd = new Random(42);
        List<String> usd = new ArrayList<>();
        List<String> eur = new ArrayList<>();
        InMemoryLedger l = world(usd, eur);
        int posted = 0;
        int refused = 0;
        for (int i = 0; i < 6_000; i++) {
            try {
                if (i % 10 == 9 && !l.journal().isEmpty()) {
                    List<Entry> j = l.journal();
                    l.reverse(j.get(rnd.nextInt(j.size())).id());
                } else {
                    l.post(randomDraft(rnd, rnd.nextBoolean() ? usd : eur));
                }
                posted++;
            } catch (LedgerException e) {
                refused++;
                assertEquals(true, e.reason() == Reason.ZERO_AMOUNT || e.reason() == Reason.INSUFFICIENT_FUNDS || e.reason() == Reason.ALREADY_REVERSED || e.reason() == Reason.UNBALANCED, e.getMessage());
            }
            if (i % 500 == 0) {
                assertEquals(0, l.trialBalance("USD"));
                assertEquals(0, l.trialBalance("EUR"));
            }
        }
        assertEquals(0, l.trialBalance("USD"));
        assertEquals(0, l.trialBalance("EUR"));
        assertEquals(posted, l.journal().size());
        assertEquals(true, posted > 2_000 && refused > 0, "posted=" + posted + " refused=" + refused);
        Map<String, Long> rebuilt = l.rebuild();
        for (String id : rebuilt.keySet()) {
            assertEquals(rebuilt.get(id), l.balance(id), id);
        }
        for (int i = 0; i < l.journal().size(); i++) {
            assertEquals(i + 1, l.journal().get(i).seq());
        }
    }
}
