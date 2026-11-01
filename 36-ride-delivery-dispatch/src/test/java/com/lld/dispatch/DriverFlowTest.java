package com.lld.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** F2: the driver state table, offer timeouts in virtual time, re-dispatch, stale accepts, and the one-offer-per-driver race. */
class DriverFlowTest {

    /** Builds a dispatcher with window 0 and a 10 second offer timeout over the given fleet. */
    private static Dispatcher dispatcher(Fleet f, ManualScheduler s) {
        return new Dispatcher(f, s, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 0, 10_000, 100);
    }

    /** Registers a driver and brings it online at x. */
    private static void online(Fleet f, long id, long x) {
        f.register(id, new Point(0, 0), 50, 10);
        f.goOnline(id, new Point(x, 0));
    }

    /** Drives one driver into the given state so the table can be tested pair by pair. */
    private static Fleet fleetInState(DriverState target, ManualScheduler s) {
        Fleet f = new Fleet(10, s::nowMs);
        f.register(1, new Point(0, 0), 50, 10);
        if (target == DriverState.OFFLINE) {
            return f;
        }
        f.goOnline(1, new Point(0, 0));
        if (target == DriverState.AVAILABLE) {
            return f;
        }
        Offer o = f.tryOffer(1, 1, 10_000).orElseThrow();
        if (target == DriverState.OFFERED) {
            return f;
        }
        f.accept(o.id());
        if (target == DriverState.EN_ROUTE) {
            return f;
        }
        f.startTrip(1);
        return f;
    }

    /** The 7 legal moves written out by hand (not read back from the table), so a wrong edge in the table fails here. */
    private static final java.util.Map<DriverState, java.util.Set<DriverState>> LEGAL = java.util.Map.of(
        DriverState.OFFLINE, java.util.EnumSet.of(DriverState.AVAILABLE),
        DriverState.AVAILABLE, java.util.EnumSet.of(DriverState.OFFLINE, DriverState.OFFERED),
        DriverState.OFFERED, java.util.EnumSet.of(DriverState.EN_ROUTE, DriverState.AVAILABLE),
        DriverState.EN_ROUTE, java.util.EnumSet.of(DriverState.ON_TRIP),
        DriverState.ON_TRIP, java.util.EnumSet.of(DriverState.AVAILABLE));

    /** Every one of the 25 state pairs is checked against the hand-written list of 7 legal moves: legal ones pass, the other 18 (including staying in the same state) throw ILLEGAL_TRANSITION. */
    @Test
    void everyStatePairMatchesTheHandWrittenLegalList() {
        int illegal = 0;
        int legal = 0;
        for (DriverState from : DriverState.values()) {
            for (DriverState to : DriverState.values()) {
                if (LEGAL.get(from).contains(to)) {
                    DriverState.check(from, to);
                    legal++;
                } else {
                    DispatchException e = assertThrows(DispatchException.class, () -> DriverState.check(from, to));
                    assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, e.code());
                    illegal++;
                }
            }
        }
        assertEquals(7, legal);
        assertEquals(18, illegal);
        for (DriverState from : DriverState.values()) {
            assertEquals(LEGAL.get(from), from.targets());
        }
    }

    /** Drives the real Fleet API from every state: goOffline, goOnline, startTrip and completeTrip each succeed only from their one legal source state and otherwise throw ILLEGAL_TRANSITION with the state unchanged, and a driver is in the grid exactly when AVAILABLE. */
    @Test
    void fleetApiRefusesEveryIllegalMoveFromEveryState() {
        for (DriverState from : DriverState.values()) {
            String[] names = {"goOffline", "goOnline", "startTrip", "completeTrip"};
            DriverState[] ok = {DriverState.AVAILABLE, DriverState.OFFLINE, DriverState.EN_ROUTE, DriverState.ON_TRIP};
            for (int m = 0; m < names.length; m++) {
                Fleet f = fleetInState(from, new ManualScheduler());
                final int which = m;
                Runnable call = () -> {
                    switch (which) {
                        case 0 -> f.goOffline(1);
                        case 1 -> f.goOnline(1, new Point(0, 0));
                        case 2 -> f.startTrip(1);
                        default -> f.completeTrip(1, new Point(0, 0));
                    }
                };
                if (from == ok[m]) {
                    call.run();
                } else {
                    DispatchException e = assertThrows(DispatchException.class, call::run, names[m] + " from " + from);
                    assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, e.code());
                    assertEquals(from, f.state(1));
                }
                int inGrid = f.withIndex(DriverIndex::size);
                assertEquals(f.state(1) == DriverState.AVAILABLE ? 1 : 0, inGrid, names[m] + " from " + from);
            }
        }
    }

    /** Offering a driver who is not AVAILABLE returns empty and changes nothing; answering with an unknown offer id is STALE_OFFER. */
    @Test
    void offerAndAnswerRefusalsChangeNothing() {
        for (DriverState from : DriverState.values()) {
            Fleet f = fleetInState(from, new ManualScheduler());
            if (from != DriverState.AVAILABLE) {
                assertEquals(Optional.empty(), f.tryOffer(1, 5, 1000));
                assertEquals(from, f.state(1));
            }
            assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> f.accept(424_242)).code());
            assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> f.reject(424_242)).code());
            assertEquals(from, f.state(1));
        }
    }

    /** The same table enforced through the Fleet API: a refused move throws and leaves the state alone. */
    @Test
    void fleetRefusesIllegalMovesAndKeepsItsState() {
        ManualScheduler s = new ManualScheduler();
        Fleet off = fleetInState(DriverState.OFFLINE, s);
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> off.startTrip(1)).code());
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> off.goOffline(1)).code());
        assertEquals(DriverState.OFFLINE, off.state(1));
        Fleet offered = fleetInState(DriverState.OFFERED, s);
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> offered.goOffline(1)).code());
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> offered.startTrip(1)).code());
        assertEquals(DriverState.OFFERED, offered.state(1));
        Fleet trip = fleetInState(DriverState.ON_TRIP, s);
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> trip.goOnline(1, new Point(0, 0))).code());
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> trip.goOffline(1)).code());
        assertEquals(DriverState.ON_TRIP, trip.state(1));
        Fleet enRoute = fleetInState(DriverState.EN_ROUTE, s);
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> enRoute.completeTrip(1, new Point(0, 0))).code());
        assertEquals(DispatchException.Code.UNKNOWN_DRIVER, assertThrows(DispatchException.class, () -> enRoute.state(77)).code());
    }

    /** The happy path: offered, accepted, trip, and back to AVAILABLE at the drop-off point (so it is findable again). */
    @Test
    void fullLifecycle() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(DriverState.OFFERED, f.state(1));
        d.accept(d.offerOf(1));
        assertEquals(Dispatcher.Status.ASSIGNED, d.status(1));
        assertEquals(DriverState.EN_ROUTE, f.state(1));
        f.startTrip(1);
        f.completeTrip(1, new Point(300, 300));
        assertEquals(DriverState.AVAILABLE, f.state(1));
        assertEquals(1, f.withIndex(ix -> ix.nearest(new Point(300, 300), id -> true)).orElseThrow().driverId());
    }

    /** No answer within the timeout: the driver is freed, and the request goes to the next best driver, never the one that timed out. */
    @Test
    void timeoutRedispatchesToTheNextBestAndSkipsTheDecliner() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        online(f, 2, 50);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(1, d.driverOf(1));
        s.advance(9_999);
        assertEquals(1, d.driverOf(1));
        s.advance(1);
        assertEquals(DriverState.AVAILABLE, f.state(1));
        assertEquals(2, d.driverOf(1));
        s.advance(10_000);
        // both drivers have now timed out; nobody is left who has not declined
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        assertEquals(DriverState.AVAILABLE, f.state(2));
    }

    /** A reject frees the driver at once and re-dispatches to the next best, excluding the decliner even though it is nearest. */
    @Test
    void rejectRedispatchesExcludingTheDecliner() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        online(f, 2, 50);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        d.reject(d.offerOf(1));
        assertEquals(2, d.driverOf(1));
        assertEquals(DriverState.AVAILABLE, f.state(1));
    }

    /** Accepting after the timeout fired is refused with STALE_OFFER and changes nothing. */
    @Test
    void acceptAfterTimeoutIsRejected() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        long stale = d.offerOf(1);
        s.advance(10_000);
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> d.accept(stale)).code());
        assertEquals(DriverState.AVAILABLE, f.state(1));
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
    }

    /** The deadline is exclusive: an accept one millisecond before it succeeds, and an accept exactly at it is already stale even before the timer task has run. */
    @Test
    void acceptJustBeforeTheDeadlineWorksAndAtTheDeadlineIsStale() {
        long[] now = {0};
        Fleet f = new Fleet(10, () -> now[0]);
        online(f, 1, 5);
        online(f, 2, 6);
        Offer early = f.tryOffer(1, 1, 1000).orElseThrow();
        Offer late = f.tryOffer(2, 2, 1000).orElseThrow();
        now[0] = 999;
        assertEquals(early, f.accept(early.id()));
        assertEquals(DriverState.EN_ROUTE, f.state(1));
        now[0] = 1000;
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> f.accept(late.id())).code());
        assertEquals(DriverState.OFFERED, f.state(2));
    }

    /** Fleet.expire called before the deadline must not free the driver (an early timer cannot revoke a live offer). */
    @Test
    void earlyExpireDoesNotRevokeALiveOffer() {
        long[] now = {0};
        Fleet f = new Fleet(10, () -> now[0]);
        online(f, 1, 5);
        Offer o = f.tryOffer(1, 1, 1000).orElseThrow();
        now[0] = 999;
        assertEquals(Optional.empty(), f.expire(o.id()));
        assertEquals(DriverState.OFFERED, f.state(1));
        assertEquals(1, f.liveOfferCount(1));
        now[0] = 1000;
        assertTrue(f.expire(o.id()).isPresent());
        assertEquals(DriverState.AVAILABLE, f.state(1));
    }

    /** The fleet clock lags the scheduler clock, so the timer fires early by the fleet's reckoning: the dispatcher re-arms for the time left instead of dropping the timer, and the offer still expires once the fleet clock catches up. */
    @Test
    void timerThatFiresEarlyIsRearmed() {
        ManualScheduler s = new ManualScheduler();
        long[] lag = {6_000};
        Fleet f = new Fleet(10, () -> s.nowMs() - lag[0]);
        online(f, 1, 5);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        s.advance(10_000);
        assertEquals(Dispatcher.Status.OFFERED, d.status(1));
        assertEquals(DriverState.OFFERED, f.state(1));
        lag[0] = 0;
        s.advance(6_000);
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        assertEquals(DriverState.AVAILABLE, f.state(1));
    }

    /** Dispatcher A cannot answer dispatcher B's offer: the stale error is raised before the fleet is touched, and both the fleet and B's request stay exactly as they were. */
    @Test
    void oneDispatcherCannotAnswerAnotherOnesOffer() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Dispatcher a = dispatcher(f, s);
        Dispatcher b = dispatcher(f, s);
        b.submit(new Request(1, new Point(0, 0)));
        long offer = b.offerOf(1);
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> a.accept(offer)).code());
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> a.reject(offer)).code());
        assertEquals(DriverState.OFFERED, f.state(1));
        assertEquals(1, f.liveOfferCount(1));
        assertEquals(Dispatcher.Status.OFFERED, b.status(1));
        assertEquals(offer, b.offerOf(1));
        b.accept(offer);
        assertEquals(Dispatcher.Status.ASSIGNED, b.status(1));
    }

    /** Null positions are refused before any state changes, and the rule "in the grid exactly when AVAILABLE" still holds after each refusal. */
    @Test
    void nullPositionsAreRefusedBeforeAnyChange() {
        Fleet f = new Fleet(10, () -> 0);
        assertThrows(NullPointerException.class, () -> f.register(1, null, 50, 10));
        assertEquals(DispatchException.Code.UNKNOWN_DRIVER, assertThrows(DispatchException.class, () -> f.state(1)).code());
        f.register(1, new Point(0, 0), 50, 10);
        assertThrows(NullPointerException.class, () -> f.goOnline(1, null));
        assertEquals(DriverState.OFFLINE, f.state(1));
        assertEquals(0, (int) f.withIndex(DriverIndex::size));
        f.goOnline(1, new Point(0, 0));
        assertThrows(NullPointerException.class, () -> f.updateLocation(1, null));
        f.tryOffer(1, 1, 1000);
        f.accept(1);
        f.startTrip(1);
        assertThrows(NullPointerException.class, () -> f.completeTrip(1, null));
        assertEquals(DriverState.ON_TRIP, f.state(1));
        assertEquals(0, (int) f.withIndex(DriverIndex::size));
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> f.register(2, new Point(0, 0), 51, 10)).code());
    }

    /** Accepting cancels the timers: nothing is left pending on the scheduler, and a later advance changes nothing. */
    @Test
    void acceptLeavesNoTimersBehind() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        assertTrue(s.pendingCount() >= 1);
        d.accept(d.offerOf(1));
        assertEquals(0, s.pendingCount());
    }

    /** An OFFERED request still counts as open demand in its cell: the second rider's surge sees 2 open requests and 0 free drivers (20,000 bps), not 1 open (15,000). */
    @Test
    void offeredRequestsCountAsSurgeDemand() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(1000, s::nowMs);
        online(f, 1, 5);
        Dispatcher d = new Dispatcher(f, s, new RatioSurgePolicy(5_000, 25_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 0, 10_000, 100);
        assertEquals(10_000, d.submit(new Request(1, new Point(0, 0))).surgeBps());
        assertEquals(Dispatcher.Status.OFFERED, d.status(1));
        assertEquals(20_000, d.submit(new Request(2, new Point(1, 1))).surgeBps());
    }

    /** Cancel of a pending request drops it; cancel of an offered one frees the driver and its timer; cancel of a finished or unknown request is refused. */
    @Test
    void cancelWorksInEveryState() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        d.cancel(1);
        assertEquals(Dispatcher.Status.CANCELLED, d.status(1));
        assertEquals(0, d.activeCount());
        assertEquals(0, s.pendingCount());
        online(f, 1, 5);
        d.submit(new Request(2, new Point(0, 0)));
        assertEquals(DriverState.OFFERED, f.state(1));
        d.cancel(2);
        assertEquals(DriverState.AVAILABLE, f.state(1));
        assertEquals(Dispatcher.Status.CANCELLED, d.status(2));
        assertEquals(0, s.pendingCount());
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> d.cancel(2)).code());
        assertEquals(DispatchException.Code.UNKNOWN_REQUEST, assertThrows(DispatchException.class, () -> d.cancel(99)).code());
        d.submit(new Request(3, new Point(0, 0)));
        d.accept(d.offerOf(3));
        assertEquals(DispatchException.Code.ILLEGAL_TRANSITION, assertThrows(DispatchException.class, () -> d.cancel(3)).code());
    }

    /** A request nobody can serve ends as EXPIRED after the maximum wait, and finished requests leave the active table so later scans stay small. */
    @Test
    void unservedRequestExpiresAndFinishedOnesArePruned() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        Dispatcher d = new Dispatcher(f, s, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 0, 10_000, 30_000, 100, Gap.NONE);
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(1, d.activeCount());
        s.advance(29_999);
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        s.advance(1);
        assertEquals(Dispatcher.Status.EXPIRED, d.status(1));
        assertEquals(0, d.activeCount());
        assertEquals(DispatchException.Code.DUPLICATE_REQUEST, assertThrows(DispatchException.class, () -> d.submit(new Request(1, new Point(0, 0)))).code());
        online(f, 1, 5);
        for (long id = 10; id < 20; id++) {
            d.submit(new Request(id, new Point(0, 0)));
            d.accept(d.offerOf(id));
            f.startTrip(1);
            f.completeTrip(1, new Point(0, 0));
        }
        assertEquals(0, d.activeCount());
        assertEquals(Dispatcher.Status.ASSIGNED, d.status(10));
    }

    /** An old offer id cannot be used on a newer offer to the same driver: ids are never reused. */
    @Test
    void oldOfferIdCannotAnswerANewOffer() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Offer first = f.tryOffer(1, 1, 1000).orElseThrow();
        f.reject(first.id());
        Offer second = f.tryOffer(1, 2, 1000).orElseThrow();
        assertTrue(second.id() > first.id());
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> f.accept(first.id())).code());
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> f.accept(999)).code());
        f.accept(second.id());
        assertEquals(DriverState.EN_ROUTE, f.state(1));
        assertEquals(DispatchException.Code.STALE_OFFER, assertThrows(DispatchException.class, () -> f.accept(second.id())).code());
    }

    /** An accepted offer's timer must not undo the acceptance when virtual time passes the old deadline. */
    @Test
    void acceptCancelsTheTimeout() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        d.accept(d.offerOf(1));
        s.advance(60_000);
        assertEquals(DriverState.EN_ROUTE, f.state(1));
        assertEquals(Dispatcher.Status.ASSIGNED, d.status(1));
    }

    /** Request errors: duplicate id and unknown id, and a refused submit leaves the first request alone. */
    @Test
    void requestErrors() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(DispatchException.Code.DUPLICATE_REQUEST, assertThrows(DispatchException.class, () -> d.submit(new Request(1, new Point(9, 9)))).code());
        assertEquals(DispatchException.Code.UNKNOWN_REQUEST, assertThrows(DispatchException.class, () -> d.status(5)).code());
        assertEquals(1, d.driverOf(1));
    }

    /** A pending request is served as soon as a driver comes online and dispatchPending is called. */
    @Test
    void pendingRequestIsServedWhenSupplyAppears() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        Dispatcher d = dispatcher(f, s);
        d.submit(new Request(1, new Point(0, 0)));
        assertEquals(Dispatcher.Status.PENDING, d.status(1));
        online(f, 1, 5);
        d.dispatchPending();
        assertEquals(1, d.driverOf(1));
    }

    /** Defect: with the racy offer step, a second dispatcher slips in between the check and the write and the same driver gets two live offers. */
    @Test
    void racyOfferGivesOneDriverTwoOffers() {
        AtomicReference<Runnable> inner = new AtomicReference<>();
        Fleet f = new Fleet(10, () -> 0, point -> {
            Runnable r = inner.getAndSet(null);
            if (r != null) {
                r.run();
            }
        }, Fleet.Mode.RACY_FOR_TESTS);
        online(f, 1, 5);
        AtomicBoolean rivalWon = new AtomicBoolean();
        inner.set(() -> rivalWon.set(f.tryOffer(1, 2, 1000).isPresent()));
        Optional<Offer> mine = f.tryOffer(1, 1, 1000);
        assertTrue(mine.isPresent());
        assertTrue(rivalWon.get());
        assertEquals(2, f.liveOfferCount(1));
    }

    /** Fix: the same interleaving on the safe fleet. The rival is proven blocked on the fleet lock while the first caller sits between check and write; then exactly one wins. */
    @Test
    void safeOfferLetsExactlyOneWin() throws Exception {
        AtomicReference<Thread> rival = new AtomicReference<>();
        AtomicBoolean rivalWon = new AtomicBoolean(true);
        AtomicBoolean rivalWasBlocked = new AtomicBoolean();
        AtomicReference<Fleet> ref = new AtomicReference<>();
        Gap gap = point -> {
            if (rival.get() != null) {
                return;
            }
            Thread t = new Thread(() -> rivalWon.set(ref.get().tryOffer(1, 2, 1000).isPresent()));
            rival.set(t);
            t.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (t.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            rivalWasBlocked.set(t.getState() == Thread.State.BLOCKED);
        };
        Fleet f = new Fleet(10, () -> 0, gap, Fleet.Mode.SAFE);
        ref.set(f);
        online(f, 1, 5);
        assertTrue(f.tryOffer(1, 1, 1000).isPresent());
        rival.get().join(5000);
        assertTrue(rivalWasBlocked.get());
        assertFalse(rivalWon.get());
        assertEquals(1, f.liveOfferCount(1));
    }

    /** Dispatcher level: while dispatcher A has picked a driver but not offered yet, dispatcher B takes that driver. A's offer is refused and its request stays pending instead of double-booking. */
    @Test
    void twoDispatchersOneDriverOnlyOneWins() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(10, s::nowMs);
        online(f, 1, 5);
        AtomicReference<Runnable> between = new AtomicReference<>();
        Dispatcher a = new Dispatcher(f, s, new RatioSurgePolicy(0, 10_000), ScoringStrategy.DISTANCE, new GreedyAssigner(), 0, 10_000, 1_000_000, 100, point -> {
            Runnable r = between.getAndSet(null);
            if (r != null) {
                r.run();
            }
        });
        Dispatcher b = dispatcher(f, s);
        between.set(() -> b.submit(new Request(2, new Point(1, 0))));
        a.submit(new Request(1, new Point(0, 0)));
        assertEquals(1, b.driverOf(2));
        assertEquals(Dispatcher.Status.PENDING, a.status(1));
        assertEquals(1, f.liveOfferCount(1));
    }
}
