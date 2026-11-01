package com.lld.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Tests for F2: concurrent bids, and the close racing a late bid. Every interleaving is forced with latches, never with sleeps.
 */
class ConcurrencyTest {

    private static final Instant END = Support.T0.plus(Duration.ofMinutes(10));

    private final Support.TestClock clock = new Support.TestClock();
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicReference<BidRejectedException.Reason> rejected = new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicBoolean armed = new AtomicBoolean(true);

    /** A gap that parks only the first caller that reaches AFTER_READ until the test releases it. */
    private Gap parkFirstAfterRead() {
        return phase -> {
            if (phase == Gap.Phase.AFTER_READ && armed.compareAndSet(true, false)) {
                reached.countDown();
                await(release);
            }
        };
    }

    /** Waits on a latch with a generous bound so a broken test fails instead of hanging. */
    private static void await(CountDownLatch l) {
        try {
            if (!l.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Waits for a future with a bound. */
    private static <T> T get(Future<T> f) throws Exception {
        return f.get(20, TimeUnit.SECONDS);
    }

    /** DEFECT: in the racy auction two bidders both read the empty state, so both are told they lead and one bid is lost (accepted says 1 though two were acknowledged). */
    @Test
    void racyAuctionAcknowledgesTwoLeadersAndLosesABid() throws Exception {
        Auction a = new RacyAuction(Support.item(), END, SoftClose.NONE, clock, parkFirstAfterRead());
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<BidReceipt> alice = ex.submit(() -> a.bid("alice", 1000));
            await(reached);
            BidReceipt bob = a.bid("bob", 500);
            release.countDown();
            BidReceipt aliceReceipt = get(alice);
            assertTrue(aliceReceipt.leading() && bob.leading(), "two winning acknowledgements");
            assertEquals(1, aliceReceipt.seq());
            assertEquals(1, bob.seq());
            assertEquals(1, a.state().accepted());
            assertEquals("alice", a.state().leader());
        } finally {
            ex.shutdownNow();
        }
    }

    /** FIX: the same interleaving on the locked auction. Bob is blocked while alice is parked, then runs second: one leader, distinct seq, price 600. */
    @Test
    void lockedAuctionSerialisesTheSameInterleaving() throws Exception {
        LockedAuction a = new LockedAuction(Support.item(), END, SoftClose.NONE, clock, parkFirstAfterRead());
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<BidReceipt> alice = ex.submit(() -> a.bid("alice", 1000));
            await(reached);
            Future<BidReceipt> bob = ex.submit(() -> a.bid("bob", 500));
            Support.awaitWaiting(a, 1);
            release.countDown();
            BidReceipt ar = get(alice);
            BidReceipt br = get(bob);
            assertEquals(1, ar.seq());
            assertEquals(2, br.seq());
            assertTrue(ar.leading());
            assertFalse(br.leading());
            assertEquals(600, a.state().price());
            assertEquals(2, a.state().accepted());
        } finally {
            ex.shutdownNow();
        }
    }

    /** DEFECT: a bid that passed the "still open" check is written after settle announced alice, so the auction ends with two different winners. */
    @Test
    void racyCloseAndLateBidGiveTwoOutcomes() throws Exception {
        RacyAuction a = new RacyAuction(Support.item(), END, SoftClose.NONE, clock, parkFirstAfterReadOnceArmed());
        a.bid("alice", 1500);
        armed.set(true);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<BidReceipt> late = ex.submit(() -> a.bid("bob", 3000));
            await(reached);
            clock.set(END);
            Outcome announced = a.settle();
            release.countDown();
            get(late);
            assertEquals("alice", announced.winner());
            Outcome later = a.settle();
            assertNotEquals(announced, later);
            assertEquals("bob", later.winner());
        } finally {
            ex.shutdownNow();
        }
    }

    /** The park gap, but disarmed at first: the test's own setup bid runs straight through, and the test arms it with {@code armed.set(true)} just before the bid it wants to park. */
    private Gap parkFirstAfterReadOnceArmed() {
        armed.set(false);
        return parkFirstAfterRead();
    }

    /** FIX: the late bid owns the lock while the close waits, so the bid is fully in before the close reads; exactly one outcome and it names bob. */
    @Test
    void lockedCloseAndLateBidGiveOneOutcome() throws Exception {
        LockedAuction a = new LockedAuction(Support.item(), END, SoftClose.NONE, clock, parkFirstAfterReadOnceArmed());
        a.bid("alice", 1500);
        armed.set(true);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<BidReceipt> late = ex.submit(() -> a.bid("bob", 3000));
            await(reached);
            clock.set(END);
            Future<Outcome> close = ex.submit(a::settle);
            Support.awaitWaiting(a, 1);
            release.countDown();
            get(late);
            Outcome announced = get(close);
            assertEquals(new Outcome(true, "bob", 1600), announced);
            assertEquals(announced, a.settle());
        } finally {
            ex.shutdownNow();
        }
    }

    /**
     * A bid that was waiting for the lock while time passed the end must be refused. Alice is parked inside the lock one nanosecond before the end; bob calls bid and blocks on the lock; the clock then moves to the end and alice finishes. Bob may read the clock only once he holds the lock, so he sees the end and gets CLOSED. If the clock were read before the lock, bob would carry the old stamp and be accepted at the end, breaking invariant I4.
     */
    @Test
    void clockIsReadInsideTheLockSoAWaitingBidSeesTheEnd() throws Exception {
        CountDownLatch aliceInside = new CountDownLatch(1);
        CountDownLatch aliceGo = new CountDownLatch(1);
        Gap gap = phase -> {
            if (phase == Gap.Phase.AFTER_READ && Thread.currentThread().getName().equals("alice")) {
                aliceInside.countDown();
                await(aliceGo);
            }
        };
        LockedAuction a = new LockedAuction(Support.item(), END, SoftClose.NONE, clock, gap);
        Thread alice = new Thread(() -> a.bid("alice", 1000), "alice");
        Thread bob = new Thread(() -> {
            try {
                a.bid("bob", 3000);
            } catch (BidRejectedException e) {
                rejected.set(e.reason());
            }
        }, "bob");
        try {
            clock.set(END.minusNanos(1));
            alice.start();
            await(aliceInside);
            bob.start();
            Support.awaitWaiting(a, 1);
            clock.set(END);
            aliceGo.countDown();
            alice.join(20_000);
            bob.join(20_000);
            assertEquals(BidRejectedException.Reason.CLOSED, rejected.get());
            assertEquals(1, a.state().accepted());
        } finally {
            aliceGo.countDown();
        }
    }

    /** The other order: once the clock reached the end the bid is refused, and the outcome is the earlier leader. */
    @Test
    void bidAfterTheCloseIsRefused() {
        LockedAuction a = new LockedAuction(Support.item(), END, SoftClose.NONE, clock);
        a.bid("alice", 1500);
        clock.set(END);
        assertThrows(BidRejectedException.class, () -> a.bid("bob", 3000));
        assertEquals(new Outcome(true, "alice", 100), a.settle());
    }

    /**
     * Start-gate hammer: 8 bidders, 250 bids each, distinct maxes, released together. Every receipt has a distinct seq 1..n, the top max leads, and replaying the accepted bids one by one in seq order gives the identical final state.
     */
    @Test
    void hammerMatchesSequentialReplay() throws Exception {
        int threads = 8;
        int rounds = 250;
        LockedAuction a = new LockedAuction(new Item("i", "sam", 0, 100), END, SoftClose.NONE, clock);
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<List<BidReceipt>>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int tt = t;
                futures.add(ex.submit(() -> {
                    await(gate);
                    List<BidReceipt> mine = new ArrayList<>();
                    for (int r = 0; r < rounds; r++) {
                        try {
                            mine.add(a.bid("b" + tt, (r * 8L + tt + 1) * 100));
                        } catch (BidRejectedException e) {
                            // too low because someone got ahead; fine
                        }
                    }
                    return mine;
                }));
            }
            gate.countDown();
            List<BidReceipt> all = new ArrayList<>();
            for (Future<List<BidReceipt>> f : futures) {
                all.addAll(get(f));
            }
            all.sort(Comparator.comparingLong(BidReceipt::seq));
            for (int i = 0; i < all.size(); i++) {
                assertEquals(i + 1L, all.get(i).seq());
            }
            LockedAuction replay = new LockedAuction(new Item("i", "sam", 0, 100), END, SoftClose.NONE, clock);
            for (BidReceipt r : all) {
                BidReceipt again = replay.bid(r.bidder(), r.max());
                assertEquals(r.leader(), again.leader());
                assertEquals(r.price(), again.price());
            }
            AuctionState live = a.state();
            assertEquals(replay.state(), live);
            assertEquals(all.size(), live.accepted());
            assertEquals("b7", live.leader());
            assertEquals(200_000, live.leaderMax());
        } finally {
            ex.shutdownNow();
        }
    }
}
