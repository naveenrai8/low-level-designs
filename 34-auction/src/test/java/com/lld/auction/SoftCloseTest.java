package com.lld.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Tests for F1: anti-sniping soft close.
 */
class SoftCloseTest {

    private static final Instant END = Support.T0.plus(Duration.ofSeconds(60));
    private static final Duration W = Duration.ofSeconds(10);

    private final Support.TestClock clock = new Support.TestClock();

    /** Builds an auction with a 10 s window and the given extension cap. */
    private Auction auction(int cap) {
        return new LockedAuction(new Item("i", "sam", 0, 100), END, new SoftClose(W, cap), clock);
    }

    /** The window starts at endsAt - W inclusive: 1 ns earlier does not extend, exactly there does. */
    @Test
    void windowBoundaryIsInclusiveAtItsStart() {
        Auction a = auction(Integer.MAX_VALUE);
        clock.set(END.minus(W).minusNanos(1));
        assertEquals(END, a.bid("alice", 500).endsAt());
        clock.set(END.minus(W));
        assertEquals(END.plus(W), a.bid("bob", 900).endsAt());
    }

    /** A chain of extensions: each late bid pushes the end back by W, and a bid at exactly the new end is still rejected. */
    @Test
    void chainOfExtensions() {
        Auction a = auction(Integer.MAX_VALUE);
        clock.set(at(50));
        assertEquals(at(70), a.bid("alice", 500).endsAt());
        clock.set(at(60));
        assertEquals(at(80), a.bid("bob", 900).endsAt());
        clock.set(at(79));
        assertEquals(at(90), a.bid("alice", 1500).endsAt());
        assertEquals(3, a.state().extensions());
        clock.set(at(90));
        assertThrows(BidRejectedException.class, () -> a.bid("bob", 5000));
    }

    /** With a cap of 2 the third late bid is still accepted but no longer moves the end, so the auction is guaranteed to finish. */
    @Test
    void capStopsExtensions() {
        Auction a = auction(2);
        clock.set(at(55));
        a.bid("alice", 500);
        clock.set(at(65));
        a.bid("bob", 900);
        clock.set(at(75));
        assertEquals(at(80), a.bid("alice", 1500).endsAt());
        assertEquals(2, a.state().extensions());
    }

    /** A rejected bid never extends the time. */
    @Test
    void rejectedBidDoesNotExtend() {
        Auction a = auction(Integer.MAX_VALUE);
        clock.set(at(55));
        assertThrows(BidRejectedException.class, () -> a.bid("sam", 500));
        assertEquals(END, a.state().endsAt());
    }

    /**
     * Why soft close finds a better price and the right winner (reasoning, not a published result). Alice values the item at 1000 and bids 700 early. Bob values it at only 900 and snipes 800 at second 55. Hard close: bob wins at 800. Soft close: alice sees the snipe, answers 1000 at second 62, and wins at 900 (bob's max 800 plus 100). The highest-value bidder wins and the seller gets more.
     */
    @Test
    void softCloseLetsTheHigherValueBidderAnswerASnipe() {
        Auction hard = new LockedAuction(new Item("i", "sam", 0, 100), END, SoftClose.NONE, clock);
        hard.bid("alice", 700);
        clock.set(at(55));
        hard.bid("bob", 800);
        clock.set(END);
        assertEquals(new Outcome(true, "bob", 800), hard.settle());

        clock.set(Support.T0);
        Auction soft = auction(Integer.MAX_VALUE);
        soft.bid("alice", 700);
        clock.set(at(55));
        soft.bid("bob", 800);
        clock.set(at(62));
        soft.bid("alice", 1000);
        clock.set(at(80));
        assertEquals(new Outcome(true, "alice", 900), soft.settle());
    }

    /** Short helper: the instant that is the given number of seconds after the start. */
    private static Instant at(int seconds) {
        return Support.T0.plusSeconds(seconds);
    }
}
