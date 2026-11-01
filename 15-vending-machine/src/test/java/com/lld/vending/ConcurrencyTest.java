package com.lld.vending;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Many customers pressing buttons at once must not break the one-customer-at-a-time rule. */
class ConcurrencyTest {

    /** 16 threads race to select a cola behind a start gate: exactly one gets in, the rest are refused by the table. */
    @Test
    void onlyOneThreadWinsSelect() throws Exception {
        Fixtures f = new Fixtures(Fixtures.working());
        int threads = 16;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> all = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            all.add(pool.submit(() -> {
                gate.await();
                try {
                    f.machine.select("A1");
                    won.incrementAndGet();
                } catch (IllegalTransitionException e) {
                    refused.incrementAndGet();
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> fu : all) {
            fu.get();
        }
        pool.shutdown();
        assertEquals(1, won.get());
        assertEquals(threads - 1, refused.get());
        assertEquals(State.SELECTING, f.machine.state());
    }

    /** 8 threads try to insert 5 coins of 5 each for a 125 price: exactly 25 are accepted and the balance is exactly 125, so no overpay and no lost update. */
    @Test
    void insertsFromManyThreadsAddUpExactly() throws Exception {
        Fixtures f = new Fixtures(Fixtures.working());
        f.machine.select("A1");
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> all = new ArrayList<>();
        AtomicInteger legal = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            all.add(pool.submit(() -> {
                gate.await();
                for (int k = 0; k < 5; k++) {
                    try {
                        f.machine.insert(5);
                        legal.incrementAndGet();
                    } catch (IllegalTransitionException e) {
                        // already PAID is fine: the product is paid for
                    }
                }
                return null;
            }));
        }
        gate.countDown();
        for (Future<?> fu : all) {
            fu.get();
        }
        pool.shutdown();
        assertEquals(25, legal.get());
        assertEquals(125, f.machine.balance());
        assertEquals(State.PAID, f.machine.state());
    }

    /** The bug class: the desk holds 100 of a 125 price. Two threads each add 25; a barrier parks both in the gap after the check, so both passed it and the desk ends at exactly 150 (overpaid). Forced every run, no timing luck. */
    @Test
    void checkThenActDeskOverpaysWhenInterleavingIsForced() throws Exception {
        CyclicBarrier bothInGap = new CyclicBarrier(2);
        CheckThenActPaymentDesk desk = new CheckThenActPaymentDesk(125, 100, () -> {
            try {
                bothInGap.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> a = pool.submit(() -> desk.insert(25));
        Future<Boolean> b = pool.submit(() -> desk.insert(25));
        assertEquals(true, a.get());
        assertEquals(true, b.get());
        pool.shutdown();
        assertEquals(150, desk.balance());
    }
}
