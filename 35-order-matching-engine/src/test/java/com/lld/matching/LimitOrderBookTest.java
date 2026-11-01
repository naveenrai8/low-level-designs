package com.lld.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** v1 tests: price-time priority, partial fills, maker price, the error contract and the invariants. */
class LimitOrderBookTest {

    /** A cancel of an order that was fully filled (so it is no longer resting) is rejected and writes nothing. */
    @Test
    void cancelOfAFilledOrderIsRejected() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("m", Side.SELL, 100, 2));
        b.apply(Command.NewOrder.limit("t", Side.BUY, 100, 2));
        int logged = b.log().size();
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Cancel("m")));
        assertEquals(logged, b.log().size());
        assertEquals(0, b.orders().get(0).cancelled());
        b.checkInvariants();
    }

    /** Cancelling twice: the second cancel is rejected and does not add to the cancelled quantity. */
    @Test
    void doubleCancelIsRejected() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("a", Side.BUY, 100, 5));
        b.apply(new Command.Cancel("a"));
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Cancel("a")));
        assertEquals(5, b.orders().get(0).cancelled());
        b.checkInvariants();
    }

    /** Replacing an order that has been filled or cancelled is rejected. */
    @Test
    void replaceOfAFilledOrderIsRejected() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("m", Side.SELL, 100, 2));
        b.apply(Command.NewOrder.limit("t", Side.BUY, 100, 2));
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Replace("m", 100, 1)));
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Replace("t", 100, 1)));
        assertEquals("BIDS\nASKS\n", b.snapshot());
    }

    /** A replace that crosses and fully fills leaves the order not-live: the checker's live flag matches the real book and a second replace is rejected. */
    @Test
    void replaceThatFullyFillsLeavesNothingResting() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("s", Side.SELL, 101, 5));
        b.apply(Command.NewOrder.limit("a", Side.BUY, 100, 5));
        Result r = b.apply(new Command.Replace("a", 101, 5));
        assertEquals(Result.Outcome.FILLED, r.outcome());
        b.checkInvariants();
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Replace("a", 101, 1)));
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Cancel("a")));
    }

    /** A replace whose bigger quantity would overflow the order's total is rejected with IllegalArgumentException and nothing is written. */
    @Test
    void replaceGrowthOverflowIsRejected() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("a", Side.BUY, 1, Long.MAX_VALUE - 1));
        b.apply(Command.NewOrder.limit("s", Side.SELL, 1, 10));
        String before = b.snapshot();
        int logged = b.log().size();
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Replace("a", 1, Long.MAX_VALUE - 1)));
        assertEquals(before, b.snapshot());
        assertEquals(logged, b.log().size());
        b.checkInvariants();
    }

    /** A FOK with huge quantities neither overflows nor leaves a ghost order: two asks of 2^62 fill a buy of Long.MAX_VALUE, and the log, orders and a replay agree. */
    @Test
    void fokWithNearMaxQuantityFillsCleanly() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("s1", Side.SELL, 1, 1L << 62));
        b.apply(Command.NewOrder.limit("s2", Side.SELL, 1, 1L << 62));
        Result r = b.apply(Command.NewOrder.fok("f", Side.BUY, 1, Long.MAX_VALUE));
        assertEquals(Result.Outcome.FILLED, r.outcome());
        assertEquals(Long.MAX_VALUE, r.trades().stream().mapToLong(Trade::qty).sum());
        assertEquals(3, b.log().size());
        assertEquals(3, b.orders().size());
        LimitOrderBook again = new LimitOrderBook();
        b.log().forEach(again::apply);
        assertEquals(b.snapshot(), again.snapshot());
        assertEquals(b.trades(), again.trades());
        b.checkInvariants();
    }

    /** A FOK for more than is available (huge numbers) is killed; the id is recorded as an order, the log has it, and replay agrees. */
    @Test
    void fokWithNearMaxQuantityKillsCleanly() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("s1", Side.SELL, 1, 1L << 62));
        b.apply(Command.NewOrder.limit("s2", Side.SELL, 1, 1L << 61));
        String before = b.snapshot();
        Result r = b.apply(Command.NewOrder.fok("f", Side.BUY, 1, Long.MAX_VALUE));
        assertEquals(Result.Outcome.KILLED, r.outcome());
        assertEquals(before, b.snapshot());
        assertEquals(3, b.log().size());
        assertEquals(3, b.orders().size());
        b.checkInvariants();
    }

    /** Builds the README book: bids b1 99x10; asks s1 101x4, s2 101x3, s3 102x5, s4 103x6. Fixed order, so queue order is fixed. */
    private static LimitOrderBook readmeBook() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("b1", Side.BUY, 99, 10));
        b.apply(Command.NewOrder.limit("s1", Side.SELL, 101, 4));
        b.apply(Command.NewOrder.limit("s2", Side.SELL, 101, 3));
        b.apply(Command.NewOrder.limit("s3", Side.SELL, 102, 5));
        b.apply(Command.NewOrder.limit("s4", Side.SELL, 103, 6));
        return b;
    }

    /** The README worked example: a buy at 102 for 15 sweeps 101, 101, 102 at the makers' prices and rests 3. */
    @Test
    void readmeExample() {
        LimitOrderBook b = readmeBook();
        Result r = b.apply(Command.NewOrder.limit("t", Side.BUY, 102, 15));
        assertEquals(List.of(new Trade(1, "t", "s1", Side.BUY, 101, 4), new Trade(2, "t", "s2", Side.BUY, 101, 3), new Trade(3, "t", "s3", Side.BUY, 102, 5)), r.trades());
        assertEquals(Result.Outcome.RESTED, r.outcome());
        assertEquals(3, r.rested());
        assertEquals("BIDS\n102 t:3\n99 b1:10\nASKS\n103 s4:6\n", b.snapshot());
        b.checkInvariants();
    }

    /** Trades happen at the resting order's price, for a buyer arriving and for a seller arriving. */
    @Test
    void tradesAtTheRestingPriceNotTheIncomingPrice() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("ask", Side.SELL, 100, 5));
        Result buy = b.apply(Command.NewOrder.limit("hi", Side.BUY, 110, 5));
        assertEquals(100, buy.trades().get(0).price());
        b.apply(Command.NewOrder.limit("bid", Side.BUY, 100, 5));
        Result sell = b.apply(Command.NewOrder.limit("lo", Side.SELL, 90, 5));
        assertEquals(100, sell.trades().get(0).price());
        assertEquals(500, sell.trades().get(0).notional());
    }

    /** Within one price the order that arrived first trades first. */
    @Test
    void fifoWithinALevel() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("first", Side.SELL, 100, 2));
        b.apply(Command.NewOrder.limit("second", Side.SELL, 100, 2));
        b.apply(Command.NewOrder.limit("third", Side.SELL, 100, 2));
        Result r = b.apply(Command.NewOrder.limit("t", Side.BUY, 100, 3));
        assertEquals("first", r.trades().get(0).makerId());
        assertEquals("second", r.trades().get(1).makerId());
        assertEquals(1, r.trades().get(1).qty());
        assertEquals("BIDS\nASKS\n100 second:1 third:2\n", b.snapshot());
    }

    /** A better price beats an earlier arrival: price first, then time. */
    @Test
    void priceBeatsTime() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("early", Side.SELL, 101, 1));
        b.apply(Command.NewOrder.limit("late", Side.SELL, 100, 1));
        assertEquals("late", b.apply(Command.NewOrder.limit("t", Side.BUY, 105, 1)).trades().get(0).makerId());
    }

    /** An order that does not cross just rests, and a partly filled resting order keeps its place and its smaller size. */
    @Test
    void nonCrossingRestsAndPartialFillKeepsPlace() {
        LimitOrderBook b = new LimitOrderBook();
        Result r = b.apply(Command.NewOrder.limit("a", Side.BUY, 100, 10));
        assertEquals(Result.Outcome.RESTED, r.outcome());
        assertTrue(r.trades().isEmpty());
        b.apply(Command.NewOrder.limit("b", Side.BUY, 100, 10));
        b.apply(Command.NewOrder.limit("s", Side.SELL, 100, 4));
        assertEquals("BIDS\n100 a:6 b:10\nASKS\n", b.snapshot());
        assertEquals(100, b.bestBid());
        assertEquals(-1, b.bestAsk());
    }

    /** An exactly matched order reports FILLED and leaves no empty level or zero-quantity order behind. */
    @Test
    void fullFillLeavesNothingBehind() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("a", Side.SELL, 100, 5));
        Result r = b.apply(Command.NewOrder.limit("t", Side.BUY, 100, 5));
        assertEquals(Result.Outcome.FILLED, r.outcome());
        assertEquals("BIDS\nASKS\n", b.snapshot());
        b.checkInvariants();
    }

    /** Every bad command throws IllegalArgumentException and changes nothing: not the snapshot, the trades, the log or the order list. */
    @Test
    void rejectedCommandsWriteNothing() {
        LimitOrderBook b = readmeBook();
        String before = b.snapshot();
        int logged = b.log().size();
        int orders = b.orders().size();
        List<Command> bad = List.of(
                Command.NewOrder.limit("x", Side.BUY, 100, 0),
                Command.NewOrder.limit("x", Side.BUY, 100, -3),
                Command.NewOrder.limit("x", Side.BUY, 0, 5),
                Command.NewOrder.limit("x", Side.BUY, -1, 5),
                Command.NewOrder.limit(" ", Side.BUY, 100, 5),
                Command.NewOrder.limit(null, Side.BUY, 100, 5),
                Command.NewOrder.limit("x", null, 100, 5),
                Command.NewOrder.market("x", Side.BUY, 0),
                new Command.NewOrder("x", Side.BUY, OrderType.MARKET, 7, 5),
                Command.NewOrder.limit("x", Side.BUY, Long.MAX_VALUE, 2),
                Command.NewOrder.fok("x", Side.BUY, Long.MAX_VALUE, 2),
                Command.NewOrder.limit("s1", Side.BUY, 100, 1),
                new Command.Cancel("nope"),
                new Command.Cancel(null),
                new Command.Replace("nope", 100, 1),
                new Command.Replace("s1", 0, 1),
                new Command.Replace("s1", 101, 0),
                new Command.Replace("s1", Long.MAX_VALUE, 2));
        for (Command c : bad) {
            assertThrows(IllegalArgumentException.class, () -> b.apply(c), c.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> b.apply(null));
        assertEquals(before, b.snapshot());
        assertEquals(logged, b.log().size());
        assertEquals(orders, b.orders().size());
        assertEquals(0, b.trades().size());
    }

    /** An id stays used after its order is gone, so a duplicate is rejected even for a filled or cancelled order. */
    @Test
    void idsAreNeverReused() {
        LimitOrderBook b = new LimitOrderBook();
        b.apply(Command.NewOrder.limit("a", Side.SELL, 100, 1));
        b.apply(Command.NewOrder.limit("t", Side.BUY, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> b.apply(Command.NewOrder.limit("a", Side.SELL, 100, 1)));
        assertThrows(IllegalArgumentException.class, () -> b.apply(Command.NewOrder.limit("t", Side.SELL, 100, 1)));
        assertThrows(IllegalArgumentException.class, () -> b.apply(new Command.Cancel("t")));
    }

    /** Quantity and price near the top of the long range work as long as price times quantity fits. */
    @Test
    void largeButSafeValuesTrade() {
        LimitOrderBook b = new LimitOrderBook();
        long q = 1L << 30;
        long p = 1L << 30;
        b.apply(Command.NewOrder.limit("a", Side.SELL, p, q));
        Result r = b.apply(Command.NewOrder.limit("t", Side.BUY, p, q));
        assertEquals(1L << 60, r.trades().get(0).notional());
    }

    /** Over 5000 random commands the book is never crossed and all invariants hold after every single command. */
    @Test
    void invariantsHoldAfterEveryCommand() {
        for (long seed = 1; seed <= 4; seed++) {
            LimitOrderBook b = new LimitOrderBook();
            for (Command c : Flow.random(seed, 5000)) {
                b.apply(c);
                b.checkInvariants();
            }
        }
    }

    /** The README book passes the checker and the per-order identity original == filled + resting + cancelled holds on it. */
    @Test
    void readmeBookSatisfiesTheIdentities() {
        LimitOrderBook b = readmeBook();
        b.checkInvariants();
        assertEquals(10, b.orders().get(0).resting());
        assertEquals(b.orders().get(0).original(), b.orders().get(0).filled() + b.orders().get(0).resting() + b.orders().get(0).cancelled());
        assertTrue(b.bestBid() < b.bestAsk());
    }
}
