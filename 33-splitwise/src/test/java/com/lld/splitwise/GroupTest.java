package com.lld.splitwise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Proves balances (v1), multi-currency settle-up (F2) and history, rebuild and concurrency (F3). */
class GroupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final FxRates RATES = FxRates.fixed(Map.of("EUR>USD", 1_080_000L, "GBP>USD", 1_270_000L));

    /** A group of alice, bob and carol with a fixed clock and fixed rates. */
    private static LockedExpenseGroup group() {
        return new LockedExpenseGroup(Set.of("alice", "bob", "carol"), CLOCK, RATES);
    }

    /** Sums a map of nets. */
    private static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The README example: Alice pays 90 for three, Bob pays 30 for Alice and Bob. Nets and simplified transfers match the table. */
    @Test
    void readmeExample() {
        LockedExpenseGroup g = group();
        g.addExpense("alice", "USD", 9000, Splits.equal(), Map.of("alice", 0L, "bob", 0L, "carol", 0L));
        g.addExpense("bob", "USD", 3000, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        assertEquals(Map.of("alice", 4500L, "bob", -1500L, "carol", -3000L), g.nets("USD"));
        assertEquals(List.of(new Transfer("carol", "alice", 3000), new Transfer("bob", "alice", 1500)), g.simplify("USD"));
        assertEquals(List.of(new Transfer("bob", "alice", 1500), new Transfer("carol", "alice", 3000)), g.pairwise("USD"));
    }

    /** A bill of 100 split three ways keeps the cent: nets still add up to 0 exactly. */
    @Test
    void netsAddUpToZeroAfterOddSplit() {
        LockedExpenseGroup g = group();
        g.addExpense("bob", "USD", 100, Splits.equal(), Map.of("alice", 0L, "bob", 0L, "carol", 0L));
        assertEquals(0L, sum(g.nets("USD")));
        assertEquals(Map.of("alice", -34L, "bob", 67L, "carol", -33L), g.nets("USD"));
    }

    /** A rejected expense (bad split, stranger, bad currency) writes nothing. */
    @Test
    void rejectedCallsChangeNothing() {
        LockedExpenseGroup g = group();
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "USD", 100, Splits.exact(), Map.of("bob", 99L)));
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("zed", "USD", 100, Splits.equal(), Map.of("bob", 0L)));
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "usd", 100, Splits.equal(), Map.of("bob", 0L)));
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "USD", 100, Splits.equal(), Map.of("zed", 0L)));
        assertThrows(IllegalArgumentException.class, () -> g.recordPayment("alice", "alice", "USD", 5));
        assertThrows(IllegalArgumentException.class, () -> g.recordPayment("alice", "bob", "USD", 0));
        assertTrue(g.history().isEmpty());
    }

    /** A settle-up is a new history line, the old expense line stays, and the debt is reduced. */
    @Test
    void paymentIsAppendedNotEdited() {
        LockedExpenseGroup g = group();
        ExpenseEntry e = g.addExpense("alice", "USD", 3000, Splits.equal(), Map.of("alice", 0L, "bob", 0L, "carol", 0L));
        g.recordPayment("bob", "alice", "USD", 1000);
        assertEquals(2, g.history().size());
        assertEquals(e, g.history().get(0));
        assertEquals(Map.of("alice", 1000L, "bob", 0L, "carol", -1000L), g.nets("USD"));
        assertTrue(g.pairwise("USD").contains(new Transfer("carol", "alice", 1000)));
        assertEquals(1, g.pairwise("USD").size());
    }

    /** Currencies are kept apart: a EUR expense does not touch USD balances. */
    @Test
    void balancesArePerCurrency() {
        LockedExpenseGroup g = group();
        g.addExpense("alice", "EUR", 1000, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        assertEquals(Map.of("alice", 500L, "bob", -500L, "carol", 0L), g.nets("EUR"));
        assertEquals(Map.of("alice", 0L, "bob", 0L, "carol", 0L), g.nets("USD"));
    }

    /** Conversion rounding is deterministic: the same nets and rate always give the same answer, and the answer adds up to 0. */
    @Test
    void conversionIsDeterministicAndNetsToZero() {
        Map<String, Long> nets = Map.of("a", 333L, "b", 333L, "c", -666L);
        Map<String, Long> first = Fx.convert(nets, 1_080_000L);
        assertEquals(first, Fx.convert(nets, 1_080_000L));
        assertEquals(0L, sum(first));
        // 333 * 1.08 = 359.64 -> 359 + remainder .64 each; -666 * 1.08 = -719.28 -> -720 + .72; floors add to -2, so +1 goes to c (.72) and then a (.64, smaller id).
        assertEquals(Map.of("a", 360L, "b", 359L, "c", -719L), first);
    }

    /** Random conversions (seeded) always add up to exactly 0 and stay within 1 unit of the exact value. */
    @Test
    void conversionPropertyHolds() {
        Random rnd = new Random(5);
        for (int round = 0; round < 2000; round++) {
            int n = 2 + rnd.nextInt(6);
            Map<String, Long> nets = new TreeMap<>();
            long s = 0;
            for (int i = 0; i < n - 1; i++) {
                long v = rnd.nextInt(2_000_001) - 1_000_000;
                nets.put("u" + i, v);
                s += v;
            }
            nets.put("u" + (n - 1), -s);
            long rate = 1 + rnd.nextInt(5_000_000);
            Map<String, Long> out = Fx.convert(nets, rate);
            assertEquals(0L, sum(out));
            nets.forEach((u, v) -> assertTrue(Math.abs(out.get(u) * FxRates.ONE - v * rate) < FxRates.ONE));
        }
    }

    /** Settle-up across EUR and USD converts only at settle time and the resulting transfers settle the converted nets. */
    @Test
    void settleUpInConvertsAtTheEnd() {
        LockedExpenseGroup g = group();
        g.addExpense("alice", "EUR", 1000, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        g.addExpense("bob", "USD", 300, Splits.equal(), Map.of("bob", 0L, "carol", 0L, "alice", 0L));
        List<Transfer> t = g.settleUpIn("USD");
        Map<String, Long> expected = new TreeMap<>(Fx.convert(g.nets("EUR"), 1_080_000L));
        g.nets("USD").forEach((u, v) -> expected.merge(u, v, Long::sum));
        Map<String, Long> left = new TreeMap<>(expected);
        for (Transfer x : t) {
            left.merge(x.from(), x.amount(), Long::sum);
            left.merge(x.to(), -x.amount(), Long::sum);
        }
        left.values().forEach(v -> assertEquals(0L, v));
        assertEquals(0L, sum(expected));
    }

    /** A missing rate fails loudly instead of guessing. */
    @Test
    void missingRateIsRejected() {
        LockedExpenseGroup g = group();
        g.addExpense("alice", "JPY", 1000, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        assertThrows(IllegalArgumentException.class, () -> g.settleUpIn("USD"));
    }

    /**
     * Differential test with an independent model: random expenses (all four strategies, two currencies, any payer) and payments between any two people. The expected nets are computed here from the returned lines only, then compared with the running nets AND with the rebuild from history.
     */
    @Test
    void rebuildEqualsIncrementalEqualsIndependentModel() {
        Random rnd = new Random(11);
        LockedExpenseGroup g = group();
        String[] users = {"alice", "bob", "carol"};
        String[] cur = {"USD", "EUR"};
        Map<String, Map<String, Long>> model = new TreeMap<>();
        for (int i = 0; i < 300; i++) {
            String c = cur[rnd.nextInt(2)];
            Map<String, Long> m = model.computeIfAbsent(c, k -> new TreeMap<>());
            if (rnd.nextInt(4) == 0) {
                String from = users[rnd.nextInt(3)];
                String to = users[(java.util.Arrays.asList(users).indexOf(from) + 1 + rnd.nextInt(2)) % 3];
                long amount = 1 + rnd.nextInt(500);
                PaymentEntry p = g.recordPayment(from, to, c, amount);
                m.merge(p.from(), p.amount(), Long::sum);
                m.merge(p.to(), -p.amount(), Long::sum);
            } else {
                Map<String, Long> in = new TreeMap<>();
                for (String u : users) {
                    if (rnd.nextBoolean() || in.isEmpty() && u.equals("carol")) {
                        in.put(u, 1L + rnd.nextInt(9));
                    }
                }
                long total = 1 + rnd.nextInt(10_000);
                SplitStrategy strategy = rnd.nextBoolean() ? Splits.equal() : Splits.shares();
                ExpenseEntry e = g.addExpense(users[rnd.nextInt(3)], c, total, strategy, in);
                m.merge(e.payer(), e.total(), Long::sum);
                e.shares().forEach((u, share) -> m.merge(u, -share, Long::sum));
            }
        }
        Map<String, Map<String, Long>> rebuilt = LockedExpenseGroup.rebuild(g.history());
        for (String c : cur) {
            Map<String, Long> expected = new TreeMap<>();
            for (String u : users) {
                expected.put(u, model.get(c).getOrDefault(u, 0L));
            }
            assertEquals(expected, g.nets(c));
            assertEquals(expected, padded(rebuilt.get(c), users));
            assertEquals(0L, sum(g.nets(c)));
            assertPairwiseMatchesNets(g, c);
        }
    }

    /** Fills in a 0 for every user who has no entry, so a rebuilt table can be compared with the group's nets. */
    private static Map<String, Long> padded(Map<String, Long> nets, String[] users) {
        Map<String, Long> out = new TreeMap<>();
        for (String u : users) {
            out.put(u, nets.getOrDefault(u, 0L));
        }
        return out;
    }

    /** Checks that, per person, money owed to them minus money they owe over the pairwise list equals their net. */
    private static void assertPairwiseMatchesNets(LockedExpenseGroup g, String currency) {
        Map<String, Long> fromPairs = new TreeMap<>();
        for (String u : g.members()) {
            fromPairs.put(u, 0L);
        }
        for (Transfer t : g.pairwise(currency)) {
            fromPairs.merge(t.to(), t.amount(), Long::sum);
            fromPairs.merge(t.from(), -t.amount(), Long::sum);
        }
        assertEquals(g.nets(currency), fromPairs);
    }

    /** Pairwise views in two currencies stay apart: a EUR debt and a USD payment must not cancel each other. */
    @Test
    void pairwiseKeepsCurrenciesApart() {
        LockedExpenseGroup g = group();
        g.addExpense("alice", "EUR", 1000, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        g.addExpense("alice", "USD", 400, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        g.recordPayment("bob", "alice", "USD", 200);
        assertEquals(List.of(new Transfer("bob", "alice", 500)), g.pairwise("EUR"));
        assertEquals(List.of(), g.pairwise("USD"));
        assertPairwiseMatchesNets(g, "EUR");
        assertPairwiseMatchesNets(g, "USD");
    }

    /** The group copies what the caller gave it: changing the input map after the call changes neither the history nor the balances. */
    @Test
    void callerMapMutationAfterwardsChangesNothing() {
        LockedExpenseGroup g = group();
        Map<String, Long> input = new java.util.HashMap<>(Map.of("alice", 0L, "bob", 0L));
        ExpenseEntry e = g.addExpense("alice", "USD", 100, Splits.equal(), input);
        Map<String, Long> before = g.nets("USD");
        input.put("carol", 0L);
        input.clear();
        assertEquals(before, g.nets("USD"));
        assertEquals(Map.of("alice", 50L, "bob", 50L), e.shares());
        assertEquals(e, g.history().get(0));
    }

    /** History lines and the list returned by history() cannot be modified from outside. */
    @Test
    void historyAndEntriesAreImmutable() {
        LockedExpenseGroup g = group();
        ExpenseEntry e = g.addExpense("alice", "USD", 100, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        assertThrows(UnsupportedOperationException.class, () -> g.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> g.history().add(e));
        assertThrows(UnsupportedOperationException.class, () -> e.shares().put("carol", 1L));
        assertEquals(1, g.history().size());
    }

    /** A history() copy taken earlier does not grow when the group grows (it is a snapshot, not a live view). */
    @Test
    void historySnapshotDoesNotGrow() {
        LockedExpenseGroup g = group();
        g.addExpense("alice", "USD", 100, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        List<Entry> snapshot = g.history();
        g.addExpense("alice", "USD", 100, Splits.equal(), Map.of("alice", 0L, "bob", 0L));
        assertEquals(1, snapshot.size());
        assertEquals(2, g.history().size());
    }

    /** Overflow throws and writes nothing: two expenses of 5e18 cannot both be added, and after the failure history and nets are exactly as before. */
    @Test
    void overflowThrowsAndChangesNothing() {
        LockedExpenseGroup g = group();
        long big = 5_000_000_000_000_000_000L;
        g.addExpense("alice", "USD", big, Splits.exact(), Map.of("bob", big));
        Map<String, Long> before = g.nets("USD");
        assertThrows(ArithmeticException.class, () -> g.addExpense("alice", "USD", big, Splits.exact(), Map.of("bob", big)));
        assertEquals(1, g.history().size());
        assertEquals(before, g.nets("USD"));
        assertEquals(0L, sum(g.nets("USD")));
        assertThrows(ArithmeticException.class, () -> g.recordPayment("alice", "bob", "USD", big));
        assertEquals(1, g.history().size());
        assertEquals(before, g.nets("USD"));
    }

    /** A misbehaving strategy cannot corrupt the group: wrong sum, a negative share, a stranger and null all throw IllegalArgumentException and write nothing. */
    @Test
    void badStrategyOutputIsRejected() {
        LockedExpenseGroup g = group();
        Map<String, SplitStrategy> bad = Map.of(
                "short", (t, in) -> Map.of("alice", t - 1),
                "negative", (t, in) -> Map.of("alice", t + 5, "bob", -5L),
                "stranger", (t, in) -> Map.of("zed", t),
                "nothing", (t, in) -> null);
        bad.forEach((name, strategy) -> {
            assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "USD", 100, strategy, Map.of("alice", 0L)), name);
            assertEquals(0, g.history().size(), name);
        });
        assertEquals(0L, sum(g.nets("USD")));
    }

    /** Null arguments are IllegalArgumentException, as documented, never NullPointerException. */
    @Test
    void nullsAreIllegalArgumentNotNpe() {
        LockedExpenseGroup g = group();
        assertThrows(IllegalArgumentException.class, () -> g.addExpense(null, "USD", 100, Splits.equal(), Map.of("alice", 0L)));
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", null, 100, Splits.equal(), Map.of("alice", 0L)));
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "USD", 100, Splits.equal(), null));
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "USD", 100, null, Map.of("alice", 0L)));
        Map<String, Long> withNullValue = new java.util.HashMap<>();
        withNullValue.put("alice", null);
        assertThrows(IllegalArgumentException.class, () -> g.addExpense("alice", "USD", 100, Splits.equal(), withNullValue));
        assertThrows(IllegalArgumentException.class, () -> g.recordPayment(null, "bob", "USD", 5));
        assertThrows(IllegalArgumentException.class, () -> g.recordPayment("alice", "bob", null, 5));
        assertThrows(IllegalArgumentException.class, () -> new LockedExpenseGroup(java.util.Collections.singleton(null), CLOCK, RATES));
    }

    /** Every method that takes a currency enforces the three-capital-letters rule, reads included. */
    @Test
    void currencyIsCheckedEverywhere() {
        LockedExpenseGroup g = group();
        for (String bad : new String[] {"usd", "US", "USDX", null}) {
            assertThrows(IllegalArgumentException.class, () -> g.nets(bad));
            assertThrows(IllegalArgumentException.class, () -> g.pairwise(bad));
            assertThrows(IllegalArgumentException.class, () -> g.simplify(bad));
            assertThrows(IllegalArgumentException.class, () -> g.settleUpIn(bad));
            assertThrows(IllegalArgumentException.class, () -> g.recordPayment("alice", "bob", bad, 5));
        }
    }

    /** The conversion refuses nets that do not add up to 0, instead of handing out a nonsense number of extra units. */
    @Test
    void conversionRejectsNetsThatDoNotSumToZero() {
        assertThrows(IllegalArgumentException.class, () -> Fx.convert(Map.of("a", 5L, "b", 3L), 1_000_000L));
        assertThrows(IllegalArgumentException.class, () -> Fx.convert(Map.of("a", -5L, "b", 3L), 1_000_000L));
    }

    /** Concurrency: 8 threads released together by a start gate add 250 expenses each; history is gap-free, in order, and the totals are exact. */
    @Test
    void concurrentExpensesKeepExactTotals() throws Exception {
        LockedExpenseGroup g = group();
        int threads = 8;
        int each = 250;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                gate.await();
                for (int i = 0; i < each; i++) {
                    g.addExpense("alice", "USD", 100, Splits.equal(), Map.of("alice", 0L, "bob", 0L, "carol", 0L));
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        int n = threads * each;
        List<Entry> h = g.history();
        assertEquals(n, h.size());
        for (int i = 0; i < n; i++) {
            assertEquals(i + 1L, h.get(i).seq());
        }
        // Every 100 splits 34/33/33 to alice/bob/carol, and alice paid it: alice net = 100n - 34n.
        assertEquals(Map.of("alice", 66L * n, "bob", -33L * n, "carol", -33L * n), g.nets("USD"));
        assertEquals(LockedExpenseGroup.rebuild(h).get("USD"), g.nets("USD"));
    }
}
