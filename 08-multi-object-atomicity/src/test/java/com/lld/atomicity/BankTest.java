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

/**
 * Proves both multi-object bugs (deadlock and a half-done transfer) and the ordered-locking fix.
 */
class BankTest {

    // ---- Bug 1: deadlock from inconsistent lock order ---------------------------------------

    /**
     * Proves that A-to-B and B-to-A transfers deadlock when locks are taken in the caller's order.
     * <p>This is deterministic: the barrier opens only when both threads hold their first lock, so each is guaranteed to then wait for the other's. The JVM's own deadlock detector confirms it, and interrupting the threads cleans up.
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
     * Proves that the same opposing transfers finish when locks are taken in id order, and the balances are unchanged.
     * <p>With a global order both threads go for account 1 first, so one simply waits. The barrier times out because nobody else is coming, and the timeout is ignored.
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

    /**
     * Proves that 16 threads making 5,000 random transfers each never create or lose money and never overdraw an account.
     * <p>The total is checked with exact {@code long} cents and no tolerance.
     */
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

    /**
     * Proves that between the two legs of a split transfer the money is in neither account.
     * <p>This is single-threaded and deterministic: the hook runs an audit at the exact moment between the legs and sees 1,700 of 2,000. The money reappears afterwards, unless the process crashed in between.
     */
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

    /**
     * Proves that an auditor running during thousands of transfers never sees a total other than 6,000.
     */
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

    /**
     * A task that returns something and may throw. Lets the tests pass lambdas that throw checked exceptions.
     */
    @FunctionalInterface
    interface Work {
        /**
         * Runs the task.
         */
        Object run() throws Exception;
    }

    /**
     * A task that gets a seed, so each thread can use its own random numbers.
     */
    @FunctionalInterface
    interface SeededWork {
        /**
         * Runs the task with this thread's seed.
         */
        void run(long seed) throws Exception;
    }

    /**
     * Runs the work on a new daemon thread and returns it. An interrupt is treated as expected, because tests interrupt threads to break a deadlock. Any other exception is rethrown unchecked.
     */
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

    /**
     * Runs the work on many threads behind a start gate and waits up to 30 seconds for each. A deadlock would show up as a timeout.
     */
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

    /**
     * Polls the JVM's deadlock detector until it reports deadlocked threads or the time runs out. Returns the thread ids, or null if there was no deadlock. The detector sees {@code ReentrantLock}s too, not just monitors.
     */
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

    /**
     * Opens n accounts with ids 1 to n, each starting with the same balance.
     */
    private static List<Account> openAccounts(int n, long eachMinor) {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            accounts.add(new Account(i + 1, eachMinor));
        }
        return accounts;
    }

    /**
     * Runs the audit and turns the checked {@code InterruptedException} into an unchecked one so it can be used inside a lambda.
     */
    private static long auditUnchecked(Bank bank, List<Account> accounts) {
        try {
            return bank.audit(accounts);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits at the barrier and turns any failure into an unchecked exception.
     */
    private static void await(CyclicBarrier barrier, long millis) {
        try {
            barrier.await(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits at the barrier but ignores a timeout or broken barrier. Used where nobody else is expected to arrive.
     */
    private static void awaitIgnoringTimeout(CyclicBarrier barrier, long millis) {
        try {
            barrier.await(millis, TimeUnit.MILLISECONDS);
        } catch (Exception expected) {
            // TimeoutException for the first arrival, BrokenBarrierException for the second
        }
    }
}
