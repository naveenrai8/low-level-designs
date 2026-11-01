package com.lld.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests for the v1 English auction: bid rules, proxy bidding, the half-open close and the reserve.
 */
class EnglishAuctionTest {

    private static final Instant END = Support.T0.plus(Duration.ofMinutes(10));

    private final Support.TestClock clock = new Support.TestClock();
    private final Auction auction = new LockedAuction(Support.item(), END, SoftClose.NONE, clock);

    /** Returns the reason a bid was rejected, failing if it was accepted. */
    private BidRejectedException.Reason rejection(Auction a, String who, long max) {
        return assertThrows(BidRejectedException.class, () -> a.bid(who, max)).reason();
    }

    /** The worked eBay-style trace from the README, step by step: leader and visible price after every bid. */
    @Test
    void workedProxyTrace() {
        BidReceipt r1 = auction.bid("alice", 1000);
        assertEquals("alice", r1.leader());
        assertEquals(100, r1.price());
        BidReceipt r2 = auction.bid("bob", 500);
        assertFalse(r2.leading());
        assertEquals("alice", r2.leader());
        assertEquals(600, r2.price());
        assertEquals(800, auction.bid("carol", 700).price());
        BidReceipt r4 = auction.bid("bob", 1000);
        assertFalse(r4.leading());
        assertEquals("alice", r4.leader());
        assertEquals(1000, r4.price());
        BidReceipt r5 = auction.bid("carol", 1500);
        assertEquals("carol", r5.leader());
        assertEquals(1100, r5.price());
        assertEquals(1100, auction.bid("carol", 2000).price());
        BidReceipt r7 = auction.bid("alice", 1900);
        assertEquals("carol", r7.leader());
        assertEquals(2000, r7.price());
        assertEquals(7, auction.state().accepted());
        assertEquals(7, r7.seq());
        clock.set(END);
        assertEquals(new Outcome(true, "carol", 2000), auction.settle());
    }

    /** The first bid needs max >= increment and shows exactly one increment, whatever the max is. */
    @Test
    void firstBidRule() {
        assertEquals(BidRejectedException.Reason.TOO_LOW, rejection(auction, "alice", 99));
        assertNull(auction.state().leader());
        assertEquals(100, auction.bid("alice", 100).price());
    }

    /** A later bid from someone else needs max >= price + increment; one unit short is rejected and writes nothing. */
    @Test
    void laterBidRule() {
        auction.bid("alice", 1000);
        AuctionState before = auction.state();
        assertEquals(BidRejectedException.Reason.TOO_LOW, rejection(auction, "bob", 199));
        assertEquals(before, auction.state());
        assertEquals(300, auction.bid("bob", 200).price());
    }

    /** An equal maximum: the earlier bid keeps the lead and the visible price becomes that maximum. */
    @Test
    void tieGoesToEarlierBid() {
        auction.bid("alice", 1000);
        BidReceipt r = auction.bid("bob", 1000);
        assertFalse(r.leading());
        assertEquals("alice", r.leader());
        assertEquals(1000, r.price());
    }

    /** The leader may raise their own maximum without moving the price, but may not lower it or repeat it. */
    @Test
    void leaderCanOnlyRaise() {
        auction.bid("alice", 1000);
        auction.bid("bob", 500);
        assertEquals(600, auction.bid("alice", 3000).price());
        assertEquals(BidRejectedException.Reason.NOT_RAISING, rejection(auction, "alice", 3000));
        assertEquals(BidRejectedException.Reason.NOT_RAISING, rejection(auction, "alice", 2000));
    }

    /** The seller cannot bid, and a blank bidder or zero max is an argument error, not a rejection. */
    @Test
    void sellerAndBadArguments() {
        assertEquals(BidRejectedException.Reason.SELLER_BID, rejection(auction, "sam", 5000));
        assertThrows(IllegalArgumentException.class, () -> auction.bid(" ", 500));
        assertThrows(IllegalArgumentException.class, () -> auction.bid(null, 500));
        assertThrows(IllegalArgumentException.class, () -> auction.bid("alice", 0));
        assertEquals(0, auction.state().accepted());
    }

    /** The close is half-open: 1 ns before the end is accepted, exactly at the end is rejected. */
    @Test
    void closeIsHalfOpenAtNanosecond() {
        clock.set(END.minusNanos(1));
        assertEquals(1, auction.bid("alice", 500).seq());
        clock.set(END);
        assertEquals(BidRejectedException.Reason.CLOSED, rejection(auction, "bob", 900));
        assertEquals(1, auction.state().accepted());
    }

    /** The same boundary at millisecond size, plus any time after the end. */
    @Test
    void closeBoundaryAtMillisecond() {
        clock.set(END.minusMillis(1));
        auction.bid("alice", 500);
        clock.set(END.plusMillis(1));
        assertEquals(BidRejectedException.Reason.CLOSED, rejection(auction, "bob", 900));
    }

    /** Settling early is a state error and writes nothing; settling twice gives the very same result object. */
    @Test
    void settleRules() {
        auction.bid("alice", 1500);
        clock.set(END.minusNanos(1));
        assertThrows(IllegalStateException.class, auction::settle);
        clock.set(END);
        Outcome first = auction.settle();
        assertSame(first, auction.settle());
        assertEquals(BidRejectedException.Reason.CLOSED, rejection(auction, "bob", 9000));
    }

    /** Reserve not met means no sale, even though there is a leader, and the visible price stays below the reserve while no max reaches it. */
    @Test
    void reserveNotMetMeansNoSale() {
        Auction a = new LockedAuction(Support.reserved(), END, SoftClose.NONE, clock);
        a.bid("alice", 999);
        assertEquals(100, a.state().price());
        assertEquals(400, a.bid("bob", 300).price());
        clock.set(END);
        assertEquals(Outcome.NO_SALE, a.settle());
    }

    /** The pinned example: once alice's max meets the reserve the price jumps to 1000, so bob's 1050 is below the new minimum 1100, and alice pays 1000. */
    @Test
    void reserveLiftsThePriceAndRaisesTheMinimumBid() {
        Auction a = new LockedAuction(Support.reserved(), END, SoftClose.NONE, clock);
        assertEquals(1000, a.bid("alice", 1200).price());
        assertEquals(BidRejectedException.Reason.TOO_LOW, rejection(a, "bob", 1050));
        clock.set(END);
        assertEquals(new Outcome(true, "alice", 1000), a.settle());
    }

    /** Leader max exactly equal to the reserve is enough to sell (the reserve is a minimum, not a strict bar). */
    @Test
    void leaderMaxEqualToReserveSells() {
        Auction a = new LockedAuction(new Item("i", "sam", 500, 100), END, SoftClose.NONE, clock);
        a.bid("alice", 500);
        clock.set(END);
        assertEquals(new Outcome(true, "alice", 500), a.settle());
    }

    /** A clock that steps backwards after the settle (an NTP correction) must not reopen the auction: the settled flag, not the time, decides. */
    @Test
    void settledAuctionStaysClosedWhenTheClockStepsBack() {
        auction.bid("alice", 1500);
        clock.set(END);
        auction.settle();
        clock.set(END.minusNanos(1));
        assertEquals(BidRejectedException.Reason.CLOSED, rejection(auction, "bob", 9000));
    }

    /** No bids at all means no sale. */
    @Test
    void noBidsNoSale() {
        clock.set(END);
        assertEquals(Outcome.NO_SALE, auction.settle());
    }

    /** Near Long.MAX_VALUE a higher challenger gets the leader's max plus the increment capped at their own max, never a wrapped negative price. */
    @Test
    void higherMaxNearOverflowCapsAtTheChallengerMax() {
        long big = Long.MAX_VALUE;
        auction.bid("alice", big - 50);
        BidReceipt r = auction.bid("bob", big);
        assertEquals("bob", r.leader());
        assertEquals(big, r.price());
    }

    /** Near Long.MAX_VALUE a lower challenger leaves the price at the leader's max when challenger plus increment would pass it, with no overflow. */
    @Test
    void lowerMaxNearOverflowCapsAtTheLeaderMax() {
        long big = Long.MAX_VALUE;
        auction.bid("alice", big);
        BidReceipt r = auction.bid("bob", big - 10);
        assertEquals("alice", r.leader());
        assertEquals(big, r.price());
        assertTrue(auction.state().price() > 0);
    }

    /** When price + increment would overflow, no further bid can meet the minimum: TOO_LOW, nothing written, never an ArithmeticException or a wrapped minimum. */
    @Test
    void minimumBidNearOverflowIsTooLow() {
        long big = Long.MAX_VALUE;
        auction.bid("alice", big);
        auction.bid("bob", big - 10);
        AuctionState before = auction.state();
        assertEquals(BidRejectedException.Reason.TOO_LOW, rejection(auction, "carol", big));
        assertEquals(before, auction.state());
    }

    /** Bad item fields are refused when the item is built. */
    @Test
    void itemValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Item("", "s", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Item("i", "s", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Item("i", "s", 0, 0));
        assertTrue(new Item("i", "s", 0, 1).increment() > 0);
    }

    /**
     * Seeded property test against a naive model. The model sees only the accepted bids: the leader is the top per-bidder max (earliest wins a tie), and the price is the increment for one bidder, else min(top, second + increment), or top on a tie.
     */
    @Test
    void matchesNaiveModelAndPriceNeverDecreases() {
        Random rnd = new Random(34);
        String[] names = {"zed", "amy", "mia", "bob"};
        for (int round = 0; round < 300; round++) {
            Support.TestClock c = new Support.TestClock();
            Auction a = new LockedAuction(new Item("i", "sam", 0, 10), END, SoftClose.NONE, c);
            Map<String, Long> best = new LinkedHashMap<>();
            Map<String, Integer> firstAt = new LinkedHashMap<>();
            long lastPrice = 0;
            String lastLeader = null;
            int accepted = 0;
            for (int i = 0; i < 25; i++) {
                String who = names[rnd.nextInt(names.length)];
                long max = 1 + rnd.nextInt(500);
                BidReceipt r;
                try {
                    r = a.bid(who, max);
                } catch (BidRejectedException e) {
                    continue;
                }
                accepted++;
                boolean wasLeader = who.equals(lastLeader);
                if (max > best.getOrDefault(who, 0L)) {
                    best.put(who, max);
                    firstAt.put(who, accepted);
                }
                assertTrue(r.price() >= lastPrice, "price must not decrease");
                List<Map.Entry<String, Long>> ranked = new ArrayList<>(best.entrySet());
                ranked.sort((x, y) -> x.getValue().equals(y.getValue()) ? Integer.compare(firstAt.get(x.getKey()), firstAt.get(y.getKey())) : Long.compare(y.getValue(), x.getValue()));
                long top = ranked.get(0).getValue();
                long expected = ranked.size() == 1 ? 10 : ranked.get(1).getValue() == top ? top : Math.min(top, ranked.get(1).getValue() + 10);
                assertEquals(ranked.get(0).getKey(), r.leader());
                // a leader raising their own max leaves the visible price where it was
                if (wasLeader) {
                    expected = lastPrice;
                }
                assertEquals(expected, r.price(), "round " + round + " bid " + i + " " + who + " " + max + " best=" + best);
                assertTrue(r.price() <= top, "price never above the leader max");
                lastPrice = r.price();
                lastLeader = r.leader();
            }
        }
    }
}
