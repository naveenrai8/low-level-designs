package com.lld.dispatch;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Owns every driver's state, position and live offer, plus the grid of drivers that are AVAILABLE. Several dispatchers may share one fleet; the offer methods are package-private so the {@link Dispatcher} is the only public way to answer an offer, and the fleet is where "one driver, one offer" is enforced.
 * <p>Invariants: a driver is in the grid exactly when its state is AVAILABLE (so in exactly one cell); a driver has at most one live offer; an offer is live from creation until it is accepted, released or expired; an offer is accepted only while the clock is below its deadline.
 * <p>Error contract: unknown driver gives UNKNOWN_DRIVER; a move not in the {@link DriverState} table gives ILLEGAL_TRANSITION; an offer that is not live (expired, rejected, accepted, never existed) gives STALE_OFFER. Every check happens before any change, so a refused call writes nothing.
 * <p>Concurrency: every mutator is synchronized on the fleet, so a check and its write are one step. The only exception is {@link Mode#RACY_FOR_TESTS}, a deliberately broken offer step that checks, calls the gap hook, then writes.
 */
public final class Fleet {

    /** SAFE is the real thing. RACY_FOR_TESTS drops the lock around check-then-write so a test can prove the defect. */
    public enum Mode { SAFE, RACY_FOR_TESTS }

    private final GridIndex index;
    private final LongSupplier nowMs;
    private final Gap gap;
    private final Mode mode;
    private final Map<Long, DriverState> states = new LinkedHashMap<>();
    private final Map<Long, DriverInfo> infos = new LinkedHashMap<>();
    private final Map<Long, Offer> liveOffers = new HashMap<>();
    private long nextOfferId = 1;

    /**
     * Builds an empty fleet. Package-private on purpose: only tests may choose the racy mode or a gap hook.
     *
     * @param cellSizeMetres grid cell size, 1..1,000,000
     * @param nowMs the clock, normally {@code scheduler::nowMs}
     * @param gap test hook, {@link Gap#NONE} in production
     * @param mode SAFE except in the defect test
     */
    Fleet(long cellSizeMetres, LongSupplier nowMs, Gap gap, Mode mode) {
        this.index = new GridIndex(cellSizeMetres);
        this.nowMs = Objects.requireNonNull(nowMs);
        this.gap = Objects.requireNonNull(gap);
        this.mode = Objects.requireNonNull(mode);
    }

    /** Builds a safe fleet with no test hook. */
    public Fleet(long cellSizeMetres, LongSupplier nowMs) {
        this(cellSizeMetres, nowMs, Gap.NONE, Mode.SAFE);
    }

    /**
     * Registers a driver, OFFLINE at a position. Registering an id twice replaces nothing and throws ILLEGAL_TRANSITION.
     *
     * @param id the driver id
     * @param where the last known position
     * @param ratingTenths 0..50
     * @param speedMps at least 1
     */
    public synchronized void register(long id, Point where, int ratingTenths, int speedMps) {
        Objects.requireNonNull(where);
        if (states.containsKey(id)) {
            throw new DispatchException(DispatchException.Code.ILLEGAL_TRANSITION, "driver already registered " + id);
        }
        if (ratingTenths < 0 || ratingTenths > 50 || speedMps < 1) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "rating " + ratingTenths + " speed " + speedMps);
        }
        states.put(id, DriverState.OFFLINE);
        infos.put(id, new DriverInfo(id, where, ratingTenths, speedMps));
    }

    /** Looks up a driver state or throws UNKNOWN_DRIVER. */
    private DriverState stateOrThrow(long id) {
        DriverState s = states.get(id);
        if (s == null) {
            throw new DispatchException(DispatchException.Code.UNKNOWN_DRIVER, "driver " + id);
        }
        return s;
    }

    /**
     * OFFLINE to AVAILABLE at a position: the driver enters the grid.
     *
     * @param id the driver
     * @param where the starting position
     */
    public synchronized void goOnline(long id, Point where) {
        Objects.requireNonNull(where);
        if (stateOrThrow(id) != DriverState.OFFLINE) {
            throw new DispatchException(DispatchException.Code.ILLEGAL_TRANSITION, states.get(id) + " -> AVAILABLE via goOnline");
        }
        setAvailable(id, where);
    }

    /**
     * AVAILABLE to OFFLINE: the driver leaves the grid. A driver holding an offer cannot go offline; it must answer first.
     *
     * @param id the driver
     */
    public synchronized void goOffline(long id) {
        DriverState.check(stateOrThrow(id), DriverState.OFFLINE);
        index.remove(id);
        states.put(id, DriverState.OFFLINE);
    }

    /** Puts a driver into the AVAILABLE state at a position, moving it between cells if it was already in the grid. */
    private void setAvailable(long id, Point where) {
        DriverInfo old = infos.get(id);
        infos.put(id, new DriverInfo(id, where, old.ratingTenths(), old.speedMps()));
        states.put(id, DriverState.AVAILABLE);
        index.upsert(id, where);
    }

    /**
     * A location update. An AVAILABLE driver moves between cells; a driver in any other state just has its stored position updated (it is not in the grid).
     *
     * @param id the driver
     * @param where the new position
     */
    public synchronized void updateLocation(long id, Point where) {
        Objects.requireNonNull(where);
        DriverState s = stateOrThrow(id);
        DriverInfo old = infos.get(id);
        infos.put(id, new DriverInfo(id, Objects.requireNonNull(where), old.ratingTenths(), old.speedMps()));
        if (s == DriverState.AVAILABLE) {
            index.upsert(id, where);
        }
    }

    /**
     * Tries to give a driver an offer. Returns empty when the driver is not AVAILABLE (someone else got there first). In SAFE mode the check and the write are one step, so of two simultaneous callers exactly one gets an offer.
     *
     * @param driverId the driver
     * @param requestId the request to offer
     * @param expiresAtMs the deadline; the offer is live while now is below it
     * @return the new offer, or empty if the driver was not available
     */
    Optional<Offer> tryOffer(long driverId, long requestId, long expiresAtMs) {
        if (mode == Mode.RACY_FOR_TESTS) {
            return offerStep(driverId, requestId, expiresAtMs);
        }
        synchronized (this) {
            return offerStep(driverId, requestId, expiresAtMs);
        }
    }

    /** The check, then the hook, then the write. Safe only when the caller holds the fleet lock. */
    private Optional<Offer> offerStep(long driverId, long requestId, long expiresAtMs) {
        DriverState s = stateOrThrow(driverId);
        if (s != DriverState.AVAILABLE) {
            return Optional.empty();
        }
        gap.at("checked");
        index.remove(driverId);
        states.put(driverId, DriverState.OFFERED);
        Offer o = new Offer(nextOfferId++, driverId, requestId, expiresAtMs);
        liveOffers.put(o.id(), o);
        return Optional.of(o);
    }

    /** Finds a live offer or throws STALE_OFFER; an offer past its deadline counts as not live even if its timer has not fired yet. */
    private Offer liveOrThrow(long offerId) {
        Offer o = liveOffers.get(offerId);
        if (o == null || nowMs.getAsLong() >= o.expiresAtMs()) {
            throw new DispatchException(DispatchException.Code.STALE_OFFER, "offer " + offerId);
        }
        return o;
    }

    /**
     * The driver says yes: OFFERED to EN_ROUTE. Refused with STALE_OFFER if the offer was already answered, expired or never existed.
     *
     * @param offerId the offer id
     * @return the offer that was accepted
     */
    synchronized Offer accept(long offerId) {
        Offer o = liveOrThrow(offerId);
        DriverState.check(states.get(o.driverId()), DriverState.EN_ROUTE);
        liveOffers.remove(offerId);
        states.put(o.driverId(), DriverState.EN_ROUTE);
        return o;
    }

    /**
     * The driver says no: OFFERED back to AVAILABLE and back into the grid. Refused with STALE_OFFER if the offer is not live.
     *
     * @param offerId the offer id
     * @return the offer that was rejected
     */
    synchronized Offer reject(long offerId) {
        Offer o = liveOrThrow(offerId);
        giveBack(o);
        return o;
    }

    /**
     * The timer fired: if the offer is still live and its deadline has come, return the driver to AVAILABLE. Does nothing (returns empty) if the offer was already answered, so a late timer never harms.
     *
     * @param offerId the offer id
     * @return the expired offer, or empty if there was nothing to expire
     */
    synchronized Optional<Offer> expire(long offerId) {
        Offer o = liveOffers.get(offerId);
        if (o == null || nowMs.getAsLong() < o.expiresAtMs()) {
            return Optional.empty();
        }
        giveBack(o);
        return Optional.of(o);
    }

    /**
     * How many milliseconds are left on a live offer, by this fleet's clock. Lets the dispatcher re-arm a timer that fired early.
     *
     * @param offerId the offer id
     * @return the time left (0 if the deadline has passed), or -1 if the offer is not live
     */
    synchronized long remainingMs(long offerId) {
        Offer o = liveOffers.get(offerId);
        return o == null ? -1 : Math.max(0, o.expiresAtMs() - nowMs.getAsLong());
    }

    /**
     * Takes a live offer back regardless of its deadline (the rider cancelled): OFFERED to AVAILABLE.
     *
     * @param offerId the offer id
     * @return the released offer, or empty if it was not live
     */
    synchronized Optional<Offer> release(long offerId) {
        Offer o = liveOffers.get(offerId);
        if (o == null) {
            return Optional.empty();
        }
        giveBack(o);
        return Optional.of(o);
    }

    /** Ends an offer and puts its driver back in the grid at the stored position. */
    private void giveBack(Offer o) {
        DriverState.check(states.get(o.driverId()), DriverState.AVAILABLE);
        liveOffers.remove(o.id());
        setAvailable(o.driverId(), infos.get(o.driverId()).where());
    }

    /**
     * Pickup done: EN_ROUTE to ON_TRIP.
     *
     * @param id the driver
     */
    public synchronized void startTrip(long id) {
        DriverState.check(stateOrThrow(id), DriverState.ON_TRIP);
        states.put(id, DriverState.ON_TRIP);
    }

    /**
     * Drop-off done: ON_TRIP to AVAILABLE at the drop-off point.
     *
     * @param id the driver
     * @param where the drop-off position
     */
    public synchronized void completeTrip(long id, Point where) {
        Objects.requireNonNull(where);
        if (stateOrThrow(id) != DriverState.ON_TRIP) {
            throw new DispatchException(DispatchException.Code.ILLEGAL_TRANSITION, states.get(id) + " -> AVAILABLE via completeTrip");
        }
        setAvailable(id, where);
    }

    /** Returns a driver's current state or throws UNKNOWN_DRIVER. */
    public synchronized DriverState state(long id) {
        return stateOrThrow(id);
    }

    /** Returns a driver's details or throws UNKNOWN_DRIVER. */
    public synchronized DriverInfo info(long id) {
        stateOrThrow(id);
        return infos.get(id);
    }

    /** Counts live offers held by one driver; the invariant says 0 or 1. */
    public synchronized int liveOfferCount(long driverId) {
        int n = 0;
        for (Offer o : liveOffers.values()) {
            if (o.driverId() == driverId) {
                n++;
            }
        }
        return n;
    }

    /** Returns the grid of AVAILABLE drivers. Callers must hold the fleet lock while using it, which {@link #withIndex} does for them. */
    public synchronized <T> T withIndex(java.util.function.Function<DriverIndex, T> use) {
        return use.apply(index);
    }
}
