package com.lld.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** No double spend and no deadlock under concurrency, with forced interleavings instead of sleeps. */
class ConcurrencyTest {

    /** Builds a ledger with two liability accounts A and B (credit-normal, no overdraft) each holding 1000, funded from an overdraft source. */
    private static InMemoryLedger twoAccounts(Gap gap, LockOrder order) {
        InMemoryLedger l = new InMemoryLedger(new TestClock(), 1000, gap, order);
        l.openAccount(Account.of("A", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.of("B", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.overdraft("src", AccountType.ASSET, "USD"));
        l.post(Draft.move("src", "A", 1_000, "fund A"));
        l.post(Draft.move("src", "B", 1_000, "fund B"));
        return l;
    }

    /** Starts two rival tasks and waits until both finish, or until the JVM reports a deadlock, or a 10 second safety bound; returns true only if both finished. */
    private static boolean bothFinish(Runnable r1, Runnable r2, boolean[] deadlockSeen) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(r1);
            Future<?> f2 = pool.submit(r2);
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!(f1.isDone() && f2.isDone())) {
                if (mx.findDeadlockedThreads() != null) {
                    deadlockSeen[0] = true;
                    return false;
                }
                if (System.nanoTime() > deadline) {
                    return false;
                }
                Thread.onSpinWait();
            }
            f1.get();
            f2.get();
            return true;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Builds a gap hook for a race of two threads. Each thread, right after taking its FIRST lock, spins until the rival also holds its first lock (the broken order) or the rival is queued on a lock (the sorted order). That forces the worst interleaving with no sleep.
     */
    private static Gap raceGap(AtomicReference<InMemoryLedger> ref, AtomicBoolean armed, AtomicInteger firstHeld) {
        ThreadLocal<Boolean> seenFirst = ThreadLocal.withInitial(() -> false);
        return point -> {
            if (!armed.get() || !point.startsWith("locked:") || seenFirst.get()) {
                return;
            }
            seenFirst.set(true);
            firstHeld.incrementAndGet();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (firstHeld.get() < 2 && !rivalQueued(ref.get()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        };
    }

    /** True if any thread is queued for the lock of account A, B, W, S or merchant (the accounts the race tests use). */
    private static boolean rivalQueued(InMemoryLedger l) {
        return l != null && (l.hasWaiters("A") || l.hasWaiters("B") || l.hasWaiters("wallet") || l.hasWaiters("merchant"));
    }

    /** Runs A-to-B and B-to-A on two threads under the given lock order; returns true if both finished. */
    private static boolean opposingTransfersFinish(LockOrder order, boolean[] deadlockSeen) throws Exception {
        AtomicReference<InMemoryLedger> ref = new AtomicReference<>();
        AtomicBoolean armed = new AtomicBoolean();
        InMemoryLedger l = twoAccounts(raceGap(ref, armed, new AtomicInteger()), order);
        ref.set(l);
        armed.set(true);
        return bothFinish(() -> l.post(Draft.move("A", "B", 10, "ab")), () -> l.post(Draft.move("B", "A", 20, "ba")), deadlockSeen);
    }

    /** Locks taken in the order the postings are written deadlock when two transfers go in opposite directions (lock-order inversion); the JVM itself reports the deadlock. */
    @Test
    void lockOrderInversionDeadlocks() throws Exception {
        boolean[] seen = new boolean[1];
        assertFalse(opposingTransfersFinish(LockOrder.AS_WRITTEN, seen));
        assertTrue(seen[0], "the JVM should report a deadlock");
    }

    /** With locks taken in sorted account-id order the same opposing transfers always finish. */
    @Test
    void sortedLockOrderNeverDeadlocks() throws Exception {
        assertTrue(opposingTransfersFinish(LockOrder.SORTED, new boolean[1]));
    }

    /** A capture (wallet to merchant) racing a post (merchant to wallet) also finishes, because capture locks in sorted order too. */
    @Test
    void captureAndOpposingPostNeverDeadlock() throws Exception {
        AtomicReference<InMemoryLedger> ref = new AtomicReference<>();
        AtomicBoolean armed = new AtomicBoolean();
        InMemoryLedger l = new InMemoryLedger(new TestClock(), 1000, raceGap(ref, armed, new AtomicInteger()), LockOrder.SORTED);
        ref.set(l);
        l.openAccount(Account.of("wallet", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.of("merchant", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.overdraft("src", AccountType.ASSET, "USD"));
        l.post(Draft.move("src", "wallet", 1_000, "fund"));
        l.post(Draft.move("src", "merchant", 1_000, "fund"));
        String hold = l.authorize("wallet", 100);
        armed.set(true);
        assertTrue(bothFinish(() -> l.capture(hold, 100, "merchant"), () -> l.post(Draft.move("merchant", "wallet", 10, "back")), new boolean[1]));
        assertEquals(0, l.trialBalance("USD"));
    }

    /** Many threads race to spend 100 from an account with 1000: exactly 10 succeed, the balance ends at 0, and the numbers add up. */
    @Test
    void hammerSpendsExactlyWhatExists() throws Exception {
        for (int round = 0; round < 5; round++) {
            InMemoryLedger l = new InMemoryLedger(new TestClock());
            l.openAccount(Account.of("wallet", AccountType.LIABILITY, "USD"));
            l.openAccount(Account.of("shop", AccountType.LIABILITY, "USD"));
            l.openAccount(Account.overdraft("src", AccountType.ASSET, "USD"));
            l.post(Draft.move("src", "wallet", 1_000, "fund"));
            int threads = 32;
            CountDownLatch gate = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(pool.submit(() -> {
                    gate.await();
                    for (int i = 0; i < 5; i++) {
                        try {
                            l.post(Draft.move("wallet", "shop", 100, "spend"));
                            ok.incrementAndGet();
                        } catch (LedgerException e) {
                            assertEquals(LedgerException.Reason.INSUFFICIENT_FUNDS, e.reason());
                            refused.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : fs) {
                f.get(5, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertEquals(10, ok.get());
            assertEquals(threads * 5 - 10, refused.get());
            assertEquals(0, l.naturalBalance("wallet"));
            assertEquals(ok.get() * 100L, l.naturalBalance("shop"));
            assertEquals(0, l.trialBalance("USD"));
        }
    }

    /** Random concurrent transfers among 6 accounts in both directions keep the books balanced and the projection equal to a rebuild. */
    @Test
    void concurrentTransfersKeepBooksBalanced() throws Exception {
        InMemoryLedger l = new InMemoryLedger(new TestClock());
        List<String> ids = List.of("a", "b", "c", "d", "e", "f");
        for (String id : ids) {
            l.openAccount(Account.of(id, AccountType.LIABILITY, "USD"));
        }
        l.openAccount(Account.overdraft("src", AccountType.ASSET, "USD"));
        for (String id : ids) {
            l.post(Draft.move("src", id, 500, "fund"));
        }
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            long seed = t;
            fs.add(pool.submit(() -> {
                java.util.Random rnd = new java.util.Random(seed);
                gate.await();
                for (int i = 0; i < 500; i++) {
                    String from = ids.get(rnd.nextInt(ids.size()));
                    String to = ids.get(rnd.nextInt(ids.size()));
                    if (!from.equals(to)) {
                        try {
                            l.post(Draft.move(from, to, 1 + rnd.nextInt(100), "t"));
                        } catch (LedgerException e) {
                            assertEquals(LedgerException.Reason.INSUFFICIENT_FUNDS, e.reason());
                        }
                    }
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> f : fs) {
            f.get(8, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, l.trialBalance("USD"));
        long total = 0;
        for (String id : ids) {
            assertTrue(l.naturalBalance(id) >= 0);
            total += l.naturalBalance(id);
        }
        assertEquals(3_000, total);
        Map<String, Long> rebuilt = l.rebuild();
        for (String id : ids) {
            assertEquals(rebuilt.get(id), l.balance(id), id);
        }
        for (int i = 0; i < l.journal().size(); i++) {
            assertEquals(i + 1, l.journal().get(i).seq());
        }
    }

    /** The broken check-then-write book overdraws when a hook between the check and the write lets a second spend in. */
    @Test
    void checkThenActRaceOverdraws() throws Exception {
        AtomicReference<Runnable> inner = new AtomicReference<>();
        RacyBalanceBook book = new RacyBalanceBook(point -> {
            Runnable r = inner.getAndSet(null);
            if (r != null) {
                r.run();
            }
        });
        book.put("acct", 100);
        // while the first spend sits between its check and its write, a second full spend runs
        inner.set(() -> assertTrue(book.spend("acct", 100)));
        assertTrue(book.spend("acct", 100));
        // both spends passed the check against 100, yet 200 left the account: overdrawn by 100
        assertEquals(-100, book.get("acct"));
    }

    /** The same interleaving against the real ledger: while the first spender sits between its check and its write, the second is provably queued on a lock (so it cannot slip in), and exactly one spend wins. */
    @Test
    void fixedLedgerClosesTheRace() throws Exception {
        CountDownLatch firstChecked = new CountDownLatch(1);
        AtomicReference<InMemoryLedger> ref = new AtomicReference<>();
        AtomicBoolean rivalWasBlocked = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean armed = new AtomicBoolean();
        Gap gap = point -> {
            if (armed.get() && point.equals("checked") && calls.incrementAndGet() == 1) {
                firstChecked.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && !(ref.get().hasWaiters("S") || ref.get().hasWaiters("W"))) {
                    Thread.onSpinWait();
                }
                rivalWasBlocked.set(ref.get().hasWaiters("S") || ref.get().hasWaiters("W"));
            }
        };
        InMemoryLedger l = new InMemoryLedger(new TestClock(), 1000, gap, LockOrder.SORTED);
        ref.set(l);
        l.openAccount(Account.of("W", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.of("S", AccountType.LIABILITY, "USD"));
        l.openAccount(Account.overdraft("src", AccountType.ASSET, "USD"));
        l.post(Draft.move("src", "W", 100, "fund"));
        armed.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> spend(l));
            assertTrue(firstChecked.await(5, TimeUnit.SECONDS));
            Future<Boolean> second = pool.submit(() -> spend(l));
            boolean a = first.get(5, TimeUnit.SECONDS);
            boolean b = second.get(5, TimeUnit.SECONDS);
            assertTrue(rivalWasBlocked.get(), "the second spender must be queued on a lock while the first is in the gap");
            assertTrue(a != b, "exactly one spend wins");
            assertEquals(0, l.naturalBalance("W"));
            assertEquals(100, l.naturalBalance("S"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Readers of trialBalance, journal and rebuild run while writers post, and every read of the trial balance is exactly 0 and every journal copy has contiguous sequence numbers. */
    @Test
    void readersAlwaysSeeConsistentBooks() throws Exception {
        InMemoryLedger l = new InMemoryLedger(new TestClock());
        List<String> ids = List.of("a", "b", "c", "d");
        for (String id : ids) {
            l.openAccount(Account.of(id, AccountType.LIABILITY, "USD"));
            l.openAccount(Account.of("x" + id, AccountType.ASSET, "USD"));
        }
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int w = t;
            writers.add(pool.submit(() -> {
                gate.await();
                for (int i = 0; i < 1_000; i++) {
                    l.post(Draft.move("x" + ids.get(w), ids.get((w + 1 + i % 3) % 4), 1 + i % 7, "w"));
                }
                return null;
            }));
        }
        Future<?> reader = pool.submit(() -> {
            gate.await();
            do {
                assertEquals(0, l.trialBalance("USD"));
                List<Entry> j = l.journal();
                for (int i = 0; i < j.size(); i++) {
                    assertEquals(i + 1, j.get(i).seq());
                }
                reads.incrementAndGet();
            } while (!done.get());
            return null;
        });
        gate.countDown();
        for (Future<?> f : writers) {
            f.get(10, TimeUnit.SECONDS);
        }
        done.set(true);
        reader.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(reads.get() > 0);
        assertEquals(4_000, l.journal().size());
    }

    /** N threads post the same key and draft behind a start gate: exactly one entry is journalled and every caller gets that same entry. */
    @Test
    void sameKeyRacersPostOnce() throws Exception {
        InMemoryLedger l = twoAccounts(Gap.NONE, LockOrder.SORTED);
        int before = l.journal().size();
        int n = 16;
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Entry>> fs = new ArrayList<>();
        Draft d = Draft.move("A", "B", 10, "once");
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                gate.await();
                return l.post(d, "same-key");
            }));
        }
        gate.countDown();
        Entry first = fs.get(0).get(5, TimeUnit.SECONDS);
        for (Future<Entry> f : fs) {
            assertSame(first, f.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(before + 1, l.journal().size());
        assertEquals(990, l.naturalBalance("A"));
    }

    /** The first poster of a key fails halfway while a second caller is blocked on the same key; the second then posts exactly once, and a later retry replays it instead of posting again. */
    @Test
    void failedFirstPosterDoesNotLeakADuplicate() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        AtomicInteger fired = new AtomicInteger();
        Gap gap = point -> {
            if (armed.get() && point.startsWith("locked:") && fired.incrementAndGet() == 1) {
                while (waiter.get() == null || waiter.get().getState() != Thread.State.BLOCKED) {
                    Thread.onSpinWait();
                }
                throw new LedgerException(LedgerException.Reason.INTERRUPTED, "simulated transient failure");
            }
        };
        InMemoryLedger l = twoAccounts(gap, LockOrder.SORTED);
        int before = l.journal().size();
        Draft d = Draft.move("A", "B", 10, "once");
        armed.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Entry> first = pool.submit(() -> l.post(d, "k"));
            while (fired.get() == 0) {
                Thread.onSpinWait();
            }
            Thread t = new Thread(() -> l.post(d, "k"));
            waiter.set(t);
            t.start();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            t.join(5_000);
            assertEquals(before + 1, l.journal().size());
            Entry again = l.post(d, "k");
            assertEquals(before + 1, l.journal().size());
            assertEquals(l.journal().get(before), again);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Tries to spend all 100 from W to S; true if it worked, false if refused for lack of funds. */
    private static boolean spend(InMemoryLedger l) {
        try {
            l.post(Draft.move("W", "S", 100, "spend"));
            return true;
        } catch (LedgerException e) {
            assertEquals(LedgerException.Reason.INSUFFICIENT_FUNDS, e.reason());
            return false;
        }
    }
}
