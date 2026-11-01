package com.lld.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** F2 tests: the single-writer engine, with producers behind a start gate and a differential against sequential replay. */
class EngineTest {

    /** A command submitted through the engine returns the same result as applying it directly. */
    @Test
    void futureCarriesTheResult() throws Exception {
        try (MatchingEngine e = new MatchingEngine(16).start()) {
            e.submit(Command.NewOrder.limit("s", Side.SELL, 100, 5)).get();
            Result r = e.submit(Command.NewOrder.limit("b", Side.BUY, 100, 3)).get();
            assertEquals(Result.Outcome.FILLED, r.outcome());
            assertEquals(100, r.trades().get(0).price());
        }
    }

    /** A bad command completes its future with IllegalArgumentException, is not logged, and the engine keeps working. */
    @Test
    void rejectionIsInTheFutureAndEngineSurvives() throws Exception {
        MatchingEngine e = new MatchingEngine(16).start();
        CompletableFuture<Result> bad = e.submit(Command.NewOrder.limit("x", Side.BUY, 100, 0));
        ExecutionException ex = assertThrows(ExecutionException.class, bad::get);
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        e.submit(Command.NewOrder.limit("ok", Side.BUY, 100, 1)).get();
        e.close();
        assertEquals(1, e.log().size());
    }

    /** After close, submit throws and close can be called again. */
    @Test
    void submitAfterCloseThrows() {
        MatchingEngine e = new MatchingEngine(4).start();
        e.close();
        e.close();
        assertThrows(IllegalStateException.class, () -> e.submit(new Command.Cancel("a")));
        assertThrows(IllegalArgumentException.class, () -> new MatchingEngine(0));
    }

    /** Close lets everything already queued finish before the thread stops, even with a tiny queue that forces producers to wait. */
    @Test
    void closeDrainsTheQueue() {
        MatchingEngine e = new MatchingEngine(2).start();
        List<CompletableFuture<Result>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fs.add(e.submit(Command.NewOrder.limit("o" + i, Side.BUY, 100 - i % 5, 1)));
        }
        e.close();
        assertFalse(e.threadAlive());
        assertTrue(fs.stream().allMatch(CompletableFuture::isDone));
        assertEquals(200, e.book().orders().size());
    }

    /**
     * Eight producers wait behind a start gate and then submit their own seeded flows at the same time. The engine's accepted-command log is the single order of events. Replaying that log sequentially into a fresh book must give the same results for every command, the same trades and the same book.
     */
    @Test
    void concurrentProducersEqualSequentialReplayOfTheLog() throws Exception {
        int producers = 8;
        int each = 1500;
        MatchingEngine e = new MatchingEngine(64).start();
        CountDownLatch gate = new CountDownLatch(1);
        Map<Command, Result> seen = new IdentityHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int id = p;
            Thread t = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                List<Map.Entry<Command, CompletableFuture<Result>>> mine = new ArrayList<>();
                Random rnd = new Random(1000 + id);
                for (int i = 0; i < each; i++) {
                    Command c = i % 7 == 6
                            ? new Command.Cancel("p" + id + "-" + rnd.nextInt(i))
                            : new Command.NewOrder("p" + id + "-" + i, rnd.nextBoolean() ? Side.BUY : Side.SELL, i % 5 == 4 ? OrderType.IOC : OrderType.LIMIT, 98 + rnd.nextInt(5), 1 + rnd.nextInt(6));
                    mine.add(Map.entry(c, e.submit(c)));
                }
                for (Map.Entry<Command, CompletableFuture<Result>> m : mine) {
                    try {
                        Result r = m.getValue().get();
                        synchronized (seen) {
                            seen.put(m.getKey(), r);
                        }
                    } catch (ExecutionException ex) {
                        // a rejected cancel (target already gone or never rested): not in the log
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        gate.countDown();
        for (Thread t : threads) {
            t.join();
        }
        e.close();
        List<Command> log = e.log();
        assertEquals(seen.size(), log.size(), "accepted commands = logged commands");
        assertTrue(log.size() > producers * each / 2);
        LimitOrderBook replay = new LimitOrderBook();
        for (Command c : log) {
            assertEquals(seen.get(c), replay.apply(c));
        }
        assertEquals(replay.snapshot(), e.book().snapshot());
        assertEquals(replay.trades(), e.book().trades());
        assertFalse(e.book().trades().isEmpty());
        ((LimitOrderBook) e.book()).checkInvariants();
    }

    /** A book that delegates to a real one but misbehaves on chosen order ids, so the engine's error handling can be tested. */
    private static final class FaultyBook implements Book {
        private final LimitOrderBook real = new LimitOrderBook();
        private volatile Runnable onReentry = () -> { };

        /** Throws a non-IllegalArgument exception for id "boom", an Error for "die", calls the re-entry hook for "re", else delegates. */
        @Override
        public Result apply(Command c) {
            if (c instanceof Command.NewOrder n) {
                if (n.id().equals("boom")) {
                    throw new IllegalStateException("simulated bug");
                }
                if (n.id().equals("die")) {
                    throw new AssertionError("simulated fatal error");
                }
                if (n.id().equals("re")) {
                    onReentry.run();
                }
            }
            return real.apply(c);
        }

        /** Delegates to the real book. */
        @Override
        public List<OrderView> resting(Side side) {
            return real.resting(side);
        }

        /** Delegates to the real book. */
        @Override
        public List<OrderView> orders() {
            return real.orders();
        }

        /** Delegates to the real book. */
        @Override
        public List<Trade> trades() {
            return real.trades();
        }

        /** Delegates to the real book. */
        @Override
        public List<Command> log() {
            return real.log();
        }
    }

    /** A non-IllegalArgument exception inside the book fails only that command's future; the engine thread survives and the next command works. */
    @Test
    void unexpectedExceptionFailsOnlyThatFuture() throws Exception {
        MatchingEngine e = new MatchingEngine(8, new FaultyBook()).start();
        CompletableFuture<Result> bad = e.submit(Command.NewOrder.limit("boom", Side.BUY, 100, 1));
        ExecutionException ex = assertThrows(ExecutionException.class, bad::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(Result.Outcome.RESTED, e.submit(Command.NewOrder.limit("ok", Side.BUY, 100, 1)).get().outcome());
        e.close();
        assertEquals(1, e.log().size());
    }

    /** A fatal Error stops the engine thread, but nobody waits forever: the failing future and every queued future complete exceptionally, and later submits throw. */
    @Test
    void errorEndsTheEngineButStrandsNoFuture() throws Exception {
        FaultyBook fb = new FaultyBook();
        MatchingEngine e = new MatchingEngine(64, fb);
        List<CompletableFuture<Result>> fs = new ArrayList<>();
        fs.add(e.submit(Command.NewOrder.limit("die", Side.BUY, 100, 1)));
        for (int i = 0; i < 20; i++) {
            fs.add(e.submit(Command.NewOrder.limit("q" + i, Side.BUY, 100, 1)));
        }
        e.start();
        for (CompletableFuture<Result> f : fs) {
            assertThrows(ExecutionException.class, f::get);
        }
        e.close();
        assertFalse(e.threadAlive());
        assertThrows(IllegalStateException.class, () -> e.submit(new Command.Cancel("x")));
    }

    /** close() called by a thread whose interrupt flag is set still stops the engine thread, and the flag is still set afterwards. */
    @Test
    void closeWhileInterruptedStillStopsTheEngine() throws Exception {
        MatchingEngine e = new MatchingEngine(4).start();
        e.submit(Command.NewOrder.limit("a", Side.BUY, 100, 1)).get();
        boolean[] flagAfter = new boolean[1];
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            e.close();
            flagAfter[0] = Thread.currentThread().isInterrupted();
        });
        t.start();
        t.join();
        assertFalse(e.threadAlive());
        assertTrue(flagAfter[0]);
        assertEquals(1, e.log().size());
    }

    /** A callback that runs on the engine thread and calls submit is refused with IllegalStateException instead of deadlocking the single writer. */
    @Test
    void submitFromTheEngineThreadIsRefused() throws Exception {
        FaultyBook fb = new FaultyBook();
        MatchingEngine e = new MatchingEngine(1, fb).start();
        java.util.concurrent.atomic.AtomicReference<Throwable> seen = new java.util.concurrent.atomic.AtomicReference<>();
        fb.onReentry = () -> {
            try {
                e.submit(new Command.Cancel("zz"));
            } catch (IllegalStateException ex) {
                seen.set(ex);
            }
        };
        e.submit(Command.NewOrder.limit("re", Side.BUY, 100, 1)).get();
        e.close();
        assertInstanceOf(IllegalStateException.class, seen.get());
    }

    /** Reading the book while the engine thread is still running is refused. */
    @Test
    void bookIsOffLimitsUntilClosed() {
        MatchingEngine e = new MatchingEngine(4).start();
        assertThrows(IllegalStateException.class, e::book);
        e.close();
        assertEquals(0, e.book().orders().size());
    }
}
