package com.lld.atomicity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BankTest {

    // ---- Bug 1: deadlock from inconsistent lock order ---------------------------------------

    /**
     * Deterministic: the barrier opens only when both threads hold their FIRST lock, so each is
     * guaranteed to then wait for the other's. The JVM's own deadlock detector confirms it.
     */
    @Test
    void bugNaiveNestedLockingDeadlocksOnOpposingTransfers() throws Exception {
        Account a = new Account(1, 1_000);
        Account b = new Account(2, 1_000);
        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);
        Bank bank = new NaiveNestedLockingBank(() -> await(bothHoldFirstLock, 5_000));

        Thread aToB = start(() -> bank.transfer(a, b, 100));
        Thread bToA = start(() -> bank.transfer(b, a, 100));

        long[] deadlocked = waitForDeadlock(5_000);
        try {
            assertNotNull(deadlocked, "expected the JVM to report a deadlock");
            assertEquals(2, deadlocked.length);
        } finally {
            aToB.interrupt(); // lockInterruptibly lets us free them; synchronized never could
            bToA.interrupt();
            aToB.join(5_000);
            bToA.join(5_000);
        }
    }

    /**
     * Same opposing transfers, same hook. With a global order both threads go for account 1
     * first, so one simply waits; the barrier times out (nobody else is coming) and is ignored.
     */
    @Test
    void fixOrderedLockingCompletesOpposingTransfers() throws Exception {
        Account a = new Account(1, 1_000);
        Account b = new Account(2, 1_000);
        CyclicBarrier neverBothHoldFirstLock = new CyclicBarrier(2);
        Bank bank = new OrderedLockingBank(() -> awaitIgnoringTimeout(neverBothHoldFirstLock, 300));

        Thread aToB = start(() -> bank.transfer(a, b, 100));
        Thread bToA = start(() -> bank.transfer(b, a, 100));
        aToB.join(5_000);
        bToA.join(5_000);

        assertFalse(aToB.isAlive() || bToA.isAlive(), "both transfers finished");
        assertEquals(1_000, a.balance());
        assertEquals(1_000, b.balance());
    }

    @Test
    void fixConservesMoneyExactlyUnderRandomConcurrentTransfers() throws Exception {
        List<Account> accounts = openAccounts(10, 1_000);
        Bank bank = new OrderedLockingBank(LockGap.NONE);

        runConcurrently(16, seed -> {
            Random random = new Random(seed);
            for (int i = 0; i < 5_000; i++) {
                Account from = accounts.get(random.nextInt(accounts.size()));
                Account to = accounts.get(random.nextInt(accounts.size()));
                if (from != to) {
                    bank.transfer(from, to, 1 + random.nextInt(300));
                }
            }
        });

        assertEquals(10_000, bank.audit(accounts), "exact: long cents, no tolerance");
        assertTrue(accounts.stream().allMatch(acc -> acc.balance() >= 0), "no overdrafts");
    }

    // ---- Bug 2: atomic parts, non-atomic whole -----------------------------------------------

    /** Single-threaded and deterministic: the hook audits at the exact moment between the legs. */
    @Test
    void bugSplitLegsExposeMoneyInNeitherAccount() throws Exception {
        Account a = new Account(1, 1_000);
        Account b = new Account(2, 1_000);
        List<Account> both = List.of(a, b);
        long[] seenMidTransfer = new long[1];
        Bank[] bank = new Bank[1];
        bank[0] = new SplitLegsBank(() -> seenMidTransfer[0] = auditUnchecked(bank[0], both));

        bank[0].transfer(a, b, 300);

        assertEquals(1_700, seenMidTransfer[0], "300 cents were in neither account");
        assertEquals(2_000, bank[0].audit(both), "it reappears afterwards — unless we crashed");
    }

    @Test
    void fixAuditUnderOrderedLocksNeverSeesMoneyInFlight() throws Exception {
        List<Account> accounts = openAccounts(6, 1_000);
        Bank bank = new OrderedLockingBank(LockGap.NONE);
        AtomicBoolean running = new AtomicBoolean(true);
        List<Long> wrongTotals = Collections.synchronizedList(new ArrayList<>());

        Thread auditor = start(() -> {
            while (running.get()) {
                long total = bank.audit(accounts);
                if (total != 6_000) {
                    wrongTotals.add(total);
                }
            }
            return null;
        });
        runConcurrently(8, seed -> {
            Random random = new Random(seed);
            for (int i = 0; i < 2_000; i++) {
                Account from = accounts.get(random.nextInt(6));
                Account to = accounts.get(random.nextInt(6));
                if (from != to) {
                    bank.transfer(from, to, 1 + random.nextInt(200));
                }
            }
        });
        running.set(false);
        auditor.join(5_000);

        assertEquals(List.of(), wrongTotals);
    }

    // ---- helpers ------------------------------------------------------------------------------

    @FunctionalInterface
    interface Work {
        Object run() throws Exception;
    }

    @FunctionalInterface
    interface SeededWork {
        void run(long seed) throws Exception;
    }

    private static Thread start(Work work) {
        Thread t = new Thread(() -> {
            try {
                work.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // expected when a test breaks a deadlock
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void runConcurrently(int threads, SeededWork work) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                long seed = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    work.run(seed);
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // a deadlock here would surface as a timeout
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static long[] waitForDeadlock(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads(); // sees ReentrantLock too
            if (ids != null) {
                return ids;
            }
            Thread.sleep(20);
        }
        return null;
    }

    private static List<Account> openAccounts(int n, long eachMinor) {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            accounts.add(new Account(i + 1, eachMinor));
        }
        return accounts;
    }

    private static long auditUnchecked(Bank bank, List<Account> accounts) {
        try {
            return bank.audit(accounts);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier, long millis) {
        try {
            barrier.await(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitIgnoringTimeout(CyclicBarrier barrier, long millis) {
        try {
            barrier.await(millis, TimeUnit.MILLISECONDS);
        } catch (Exception expected) {
            // TimeoutException for the first arrival, BrokenBarrierException for the second
        }
    }
}
