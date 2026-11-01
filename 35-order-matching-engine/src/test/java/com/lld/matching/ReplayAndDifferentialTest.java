package com.lld.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** F3 tests: cancel, replace, event-log replay, and the differential against the naive reference. */
class ReplayAndDifferentialTest {

    /** Builds three bids at 100: a, b, c in that order. */
    private static LimitOrderBook threeBids() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("a", Side.BUY, 100, 5));
        b.apply(Command.NewOrder.limit("b", Side.BUY, 100, 5));
        b.apply(Command.NewOrder.limit("c", Side.BUY, 100, 5));
        return b;
    }

    /** Cancel removes the whole remainder, empties the level and counts it as cancelled quantity. */
    @Test
    void cancelRemovesTheRemainder() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("a", Side.BUY, 100, 5));
        b.apply(Command.NewOrder.limit("s", Side.SELL, 100, 2));
        Result r = b.apply(new Command.Cancel("a"));
        assertEquals(3, r.cancelled());
        assertEquals("BIDS\nASKS\n", b.snapshot());
        OrderView a = b.orders().get(0);
        assertEquals(5, a.filled() + a.cancelled());
        b.checkInvariants();
    }

    /** A quantity decrease at the same price keeps the place in the queue. */
    @Test
    void replaceSmallerKeepsPriority() {
        LimitOrderBook b = threeBids();
        Result shrinkResult = b.apply(new Command.Replace("a", 100, 2));
        assertEquals("BIDS\n100 a:2 b:5 c:5\nASKS\n", b.snapshot());
        assertEquals(3, b.orders().get(0).cancelled());
        assertEquals(3, shrinkResult.cancelled());
        b.checkInvariants();
    }

    /** A quantity increase sends the order to the back of the queue. */
    @Test
    void replaceBiggerLosesPriority() {
        LimitOrderBook b = threeBids();
        b.apply(new Command.Replace("a", 100, 8));
        assertEquals("BIDS\n100 b:5 c:5 a:8\nASKS\n", b.snapshot());
        assertEquals(8, b.orders().get(0).original());
        b.checkInvariants();
    }

    /** A price change sends the order to the back of its new level. */
    @Test
    void replacePriceLosesPriority() {
        LimitOrderBook b = threeBids();
        b.apply(new Command.Replace("a", 99, 5));
        b.apply(new Command.Replace("b", 99, 5));
        b.apply(new Command.Replace("a", 100, 5));
        assertEquals("BIDS\n100 c:5 a:5\n99 b:5\nASKS\n", b.snapshot());
    }

    /** A replace whose new price crosses trades at once, at the resting price, and rests any remainder. */
    @Test
    void replaceThatCrossesTrades() {
        LimitOrderBook b = threeBids();
        b.apply(Command.NewOrder.limit("s", Side.SELL, 105, 3));
        Result r = b.apply(new Command.Replace("a", 106, 5));
        assertEquals(1, r.trades().size());
        assertEquals(105, r.trades().get(0).price());
        assertEquals("a", r.trades().get(0).takerId());
        assertEquals(2, r.rested());
        assertEquals(0, r.cancelled());
        assertEquals("BIDS\n106 a:2\n100 b:5 c:5\nASKS\n", b.snapshot());
        b.checkInvariants();
    }

    /** Replacing a partly filled order down below what it has left keeps its place; "new qty" means quantity still wanted. */
    @Test
    void replaceAfterPartialFill() {
        LimitOrderBook b = threeBids();
        b.apply(Command.NewOrder.limit("s", Side.SELL, 100, 3));
        b.apply(new Command.Replace("a", 100, 1));
        assertEquals("BIDS\n100 a:1 b:5 c:5\nASKS\n", b.snapshot());
        OrderView a = b.orders().get(0);
        assertEquals(5, a.original());
        assertEquals(3, a.filled());
        assertEquals(1, a.resting());
        assertEquals(1, a.cancelled());
    }

    /** Replaying the accepted-command log into a fresh book gives the same snapshot, trades, orders and log, over thousands of random commands for several seeds. */
    @Test
    void replayReproducesTheBookAndTheTrades() {
        for (long seed = 100; seed < 108; seed++) {
            LimitOrderBook original = new LimitOrderBook();
            for (Command c : Flow.random(seed, 4000)) {
                original.apply(c);
            }
            LimitOrderBook replayed = new LimitOrderBook();
            for (Command c : original.log()) {
                replayed.apply(c);
            }
            assertEquals(original.snapshot(), replayed.snapshot());
            assertEquals(original.trades(), replayed.trades());
            assertEquals(original.orders(), replayed.orders());
            assertEquals(original.log(), replayed.log());
            assertTrue(original.trades().size() > 100, "the flow must trade");
        }
    }

    /** The replay test can fail: dropping one command from the log gives a different book. */
    @Test
    void replayTestIsNotVacuous() {
        LimitOrderBook original = new LimitOrderBook();
        for (Command c : Flow.random(5, 500)) {
            original.apply(c);
        }
        LimitOrderBook partial = new LimitOrderBook();
        List<Command> log = new java.util.ArrayList<>(original.log());
        java.util.Collections.swap(log, 0, 1);
        for (Command c : log) {
            try {
                partial.apply(c);
            } catch (IllegalArgumentException ignored) {
                // a swapped command may now be invalid
            }
        }
        assertNotEquals(original.trades(), partial.trades());
    }

    /** A replace that shrinks while also changing price reports the removed quantity in Result.cancelled (the crossing-branch result). */
    @Test
    void replaceShrinkWithPriceChangeReportsCancelled() {
        LimitOrderBook b = threeBids();
        Result r = b.apply(new Command.Replace("a", 99, 2));
        assertEquals(3, r.cancelled());
        assertEquals(2, r.rested());
        assertEquals("BIDS\n100 b:5 c:5\n99 a:2\nASKS\n", b.snapshot());
    }

    /** The optimised book and the naive list-scan matcher give identical trades and an identical final book, and identical snapshots every 50 commands. */
    @Test
    void matchesTheNaiveReference() {
        for (long seed = 200; seed < 212; seed++) {
            LimitOrderBook fast = new LimitOrderBook();
            NaiveBook slow = new NaiveBook();
            int i = 0;
            for (Command c : Flow.random(seed, 3000)) {
                int before = slow.trades.size();
                Result r = fast.apply(c);
                slow.apply(c);
                assertEquals(slow.trades.subList(before, slow.trades.size()), r.trades(), "seed " + seed + " step " + i);
                if (++i % 50 == 0) {
                    assertEquals(slow.snapshot(), fast.snapshot(), "seed " + seed + " step " + i);
                }
            }
            assertEquals(slow.trades, fast.trades(), "seed " + seed);
            assertEquals(slow.snapshot(), fast.snapshot());
        }
    }
}
