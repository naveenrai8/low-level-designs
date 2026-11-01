package com.lld.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Tests for F3: sealed-bid second-price (Vickrey) auction.
 */
class SealedBidTest {

    private static final Instant END = Support.T0.plus(Duration.ofMinutes(10));

    private final Support.TestClock clock = new Support.TestClock();
    private final SealedBidAuction auction = new SealedBidAuction(new Item("i", "sam", 500, 1), END, clock);

    /** Builds a bid list in the given arrival order (seq 1, 2, 3...). */
    private static List<SealedBid> bids(Object... pairs) {
        List<SealedBid> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new SealedBid(i / 2 + 1L, (String) pairs[i], ((Number) pairs[i + 1]).longValue()));
        }
        return out;
    }

    /** The winner pays the second-highest bid, not their own. */
    @Test
    void winnerPaysSecondHighest() {
        assertEquals(new Outcome(true, "zed", 800), Vickrey.settle(bids("amy", 800, "zed", 900, "bob", 700), 500));
    }

    /** One bid above the reserve: the winner pays the reserve. */
    @Test
    void singleBidPaysReserve() {
        assertEquals(new Outcome(true, "amy", 500), Vickrey.settle(bids("amy", 900), 500));
    }

    /** The second price is lifted to the reserve when it is lower. */
    @Test
    void secondBelowReservePaysReserve() {
        assertEquals(new Outcome(true, "amy", 500), Vickrey.settle(bids("amy", 900, "bob", 200), 500));
    }

    /** A top bid under the reserve, or no bids, is no sale. */
    @Test
    void belowReserveOrEmptyIsNoSale() {
        assertEquals(Outcome.NO_SALE, Vickrey.settle(bids("amy", 499), 500));
        assertEquals(Outcome.NO_SALE, Vickrey.settle(List.of(), 500));
    }

    /** Tie at the top: the earlier bid wins (smaller seq, even if listed later) and pays the tied amount. */
    @Test
    void tieGoesToEarlierBid() {
        List<SealedBid> list = List.of(new SealedBid(2, "late", 900), new SealedBid(1, "early", 900));
        assertEquals(new Outcome(true, "early", 900), Vickrey.settle(list, 500));
    }

    /**
     * Truthful bidding is never worse: for a bidder with value v against seeded random rivals, the utility of bidding v is at least the utility of any sampled other bid b, whether the bidder arrives first or last.
     */
    @Test
    void biddingTrueValueIsNeverWorseThanShading() {
        Random rnd = new Random(1961);
        for (int round = 0; round < 3000; round++) {
            long v = 1 + rnd.nextInt(1000);
            long reserve = rnd.nextInt(600);
            int n = rnd.nextInt(5);
            List<Long> others = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                others.add(1L + rnd.nextInt(1200));
            }
            for (boolean first : new boolean[] {true, false}) {
                long truthful = utility(v, v, others, reserve, first);
                assertTrue(truthful >= 0, "truthful bidding never loses money");
                for (int k = 0; k < 20; k++) {
                    long b = 1 + rnd.nextInt(1500);
                    assertTrue(truthful >= utility(v, b, others, reserve, first), "v=" + v + " b=" + b + " others=" + others);
                }
            }
        }
    }

    /** My utility if I bid b with true value v: v minus the price if I win, else 0. */
    private static long utility(long v, long b, List<Long> others, long reserve, boolean iAmFirst) {
        List<SealedBid> list = new ArrayList<>();
        long seq = iAmFirst ? 2 : 1;
        for (long o : others) {
            list.add(new SealedBid(seq++, "r" + seq, o));
        }
        list.add(new SealedBid(iAmFirst ? 1 : seq, "me", b));
        Outcome o = Vickrey.settle(list, reserve);
        return o.sold() && o.winner().equals("me") ? v - o.pays() : 0;
    }

    /** Proxy bidding is second price in disguise. The maxes are placed in rising order, so every one is accepted (a bid under price + increment would be rejected, and then the two prices differ by less than one increment). the English price is the Vickrey price plus one increment (capped at the winner's max). */
    @Test
    void proxyBiddingMatchesSecondPrice() {
        Random rnd = new Random(7);
        for (int round = 0; round < 200; round++) {
            Support.TestClock c = new Support.TestClock();
            Auction english = new LockedAuction(new Item("i", "sam", 0, 1), END, SoftClose.NONE, c);
            List<SealedBid> sealed = new ArrayList<>();
            java.util.TreeSet<Long> used = new java.util.TreeSet<>();
            while (used.size() < 4) {
                used.add(2L + rnd.nextInt(1000));
            }
            List<Long> maxes = new ArrayList<>(used);
            for (long m : maxes) {
                english.bid("p" + m, m);
            }
            java.util.Collections.shuffle(maxes, rnd);
            for (int i = 0; i < maxes.size(); i++) {
                sealed.add(new SealedBid(i + 1L, "p" + maxes.get(i), maxes.get(i)));
            }
            Outcome vick = Vickrey.settle(sealed, 0);
            c.set(END);
            Outcome eng = english.settle();
            assertEquals(vick.winner(), eng.winner());
            assertEquals(vick.pays() + 1, eng.pays());
        }
    }

    /** A single bid exactly equal to the reserve sells, at the reserve. */
    @Test
    void bidEqualToReserveSells() {
        assertEquals(new Outcome(true, "amy", 500), Vickrey.settle(bids("amy", 500), 500));
    }

    /** After settle, a clock stepping back before the end must not let a late sealed bid in. */
    @Test
    void settledSealedAuctionStaysClosedWhenTheClockStepsBack() {
        auction.submit("amy", 900);
        clock.set(END);
        auction.settle();
        clock.set(END.minusNanos(1));
        assertEquals(BidRejectedException.Reason.CLOSED, assertThrows(BidRejectedException.class, () -> auction.submit("bob", 950)).reason());
    }

    /** The auction collects sealed bids until the end; a bid at exactly the end is rejected and 1 ns before is accepted. */
    @Test
    void submitBoundary() {
        clock.set(END.minusNanos(1));
        assertEquals(1, auction.submit("amy", 900));
        clock.set(END);
        assertEquals(BidRejectedException.Reason.CLOSED, assertThrows(BidRejectedException.class, () -> auction.submit("bob", 950)).reason());
    }

    /** One sealed bid per bidder, the seller cannot bid, and bad arguments are refused. */
    @Test
    void submitRules() {
        auction.submit("amy", 900);
        assertEquals(BidRejectedException.Reason.DUPLICATE, assertThrows(BidRejectedException.class, () -> auction.submit("amy", 950)).reason());
        assertEquals(BidRejectedException.Reason.SELLER_BID, assertThrows(BidRejectedException.class, () -> auction.submit("sam", 950)).reason());
        assertThrows(IllegalArgumentException.class, () -> auction.submit("bob", 0));
    }

    /** Settle waits for the end, then returns the Vickrey result, the same object every time. */
    @Test
    void settleAfterEnd() {
        auction.submit("amy", 900);
        auction.submit("bob", 700);
        clock.set(END.minusNanos(1));
        assertThrows(IllegalStateException.class, auction::settle);
        clock.set(END);
        Outcome o = auction.settle();
        assertEquals(new Outcome(true, "amy", 700), o);
        assertSame(o, auction.settle());
    }
}
