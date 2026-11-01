package com.lld.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** F1 tests: market, IOC and FOK orders. */
class OrderTypesTest {

    /** Builds asks 101x4 (s1), 102x5 (s2) and bid 99x10 (b1). */
    private static LimitOrderBook book() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("b1", Side.BUY, 99, 10));
        b.apply(Command.NewOrder.limit("s1", Side.SELL, 101, 4));
        b.apply(Command.NewOrder.limit("s2", Side.SELL, 102, 5));
        return b;
    }

    /** A market order takes whatever is there at any price and its unfilled remainder is cancelled, never rested. */
    @Test
    void marketSweepsAndNeverRests() {
        LimitOrderBook b = book();
        Result r = b.apply(Command.NewOrder.market("m", Side.BUY, 20));
        assertEquals(2, r.trades().size());
        assertEquals(101, r.trades().get(0).price());
        assertEquals(102, r.trades().get(1).price());
        assertEquals(Result.Outcome.CANCELLED, r.outcome());
        assertEquals(11, r.cancelled());
        assertEquals(0, r.rested());
        assertEquals("BIDS\n99 b1:10\nASKS\n", b.snapshot());
        OrderView m = b.orders().stream().filter(o -> o.id().equals("m")).findFirst().orElseThrow();
        assertEquals(20, m.filled() + m.cancelled());
        b.checkInvariants();
    }

    /** A market order into an empty side is fully cancelled and writes no trade. */
    @Test
    void marketIntoEmptyBookIsCancelled() {
        LimitOrderBook b = new LimitOrderBook();
        Result r = b.apply(Command.NewOrder.market("m", Side.SELL, 5));
        assertEquals(Result.Outcome.CANCELLED, r.outcome());
        assertEquals(5, r.cancelled());
        assertTrue(b.trades().isEmpty());
    }

    /** IOC fills what crosses now (not beyond its limit) and cancels the rest instead of resting it. */
    @Test
    void iocFillsWhatCrossesAndCancelsTheRest() {
        LimitOrderBook b = book();
        Result r = b.apply(Command.NewOrder.ioc("i", Side.BUY, 101, 10));
        assertEquals(1, r.trades().size());
        assertEquals(4, r.trades().get(0).qty());
        assertEquals(6, r.cancelled());
        assertEquals("BIDS\n99 b1:10\nASKS\n102 s2:5\n", b.snapshot());
        b.checkInvariants();
    }

    /** An IOC that crosses nothing is cancelled whole and the book is untouched. */
    @Test
    void iocThatCrossesNothingDoesNothing() {
        LimitOrderBook b = book();
        String before = b.snapshot();
        Result r = b.apply(Command.NewOrder.ioc("i", Side.SELL, 100, 3));
        assertEquals(3, r.cancelled());
        assertEquals(before, b.snapshot());
    }

    /** A FOK that can be filled completely trades like a normal order. */
    @Test
    void fokFillsWhenEnoughCrosses() {
        LimitOrderBook b = book();
        Result r = b.apply(Command.NewOrder.fok("f", Side.BUY, 102, 9));
        assertEquals(Result.Outcome.FILLED, r.outcome());
        assertEquals(2, r.trades().size());
        assertEquals("BIDS\n99 b1:10\nASKS\n", b.snapshot());
    }

    /** A FOK that cannot be filled completely is killed and the book is byte-for-byte the same, with no trades and no new log gap. */
    @Test
    void fokThatCannotFillLeavesTheBookUnchanged() {
        LimitOrderBook b = book();
        String before = b.snapshot();
        Result r = b.apply(Command.NewOrder.fok("f", Side.BUY, 102, 10));
        assertEquals(Result.Outcome.KILLED, r.outcome());
        assertTrue(r.trades().isEmpty());
        assertEquals(10, r.cancelled());
        assertEquals(before, b.snapshot());
        assertEquals(0, b.trades().size());
        assertEquals(4, b.log().size());
        b.checkInvariants();
    }

    /** The FOK pre-check respects the limit price: plenty of quantity exists, but not at a crossing price, so it is killed. */
    @Test
    void fokRespectsItsLimitPrice() {
        LimitOrderBook b = book();
        String before = b.snapshot();
        assertEquals(Result.Outcome.KILLED, b.apply(Command.NewOrder.fok("f", Side.BUY, 101, 5)).outcome());
        assertEquals(before, b.snapshot());
    }

    /** Across many random flows, every killed FOK leaves the snapshot equal to the one taken just before it. */
    @Test
    void randomKilledFoksNeverTouchTheBook() {
        LimitOrderBook b = new LimitOrderBook();
        int kills = 0;
        for (Command c : Flow.random(77, 6000)) {
            String before = b.snapshot();
            int trades = b.trades().size();
            Result r = b.apply(c);
            if (r.outcome() == Result.Outcome.KILLED) {
                kills++;
                assertEquals(before, b.snapshot());
                assertEquals(trades, b.trades().size());
            }
        }
        assertTrue(kills > 20, "the flow must actually exercise FOK kills: " + kills);
    }
}
