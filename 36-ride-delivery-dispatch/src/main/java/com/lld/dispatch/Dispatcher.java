package com.lld.dispatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Takes requests, prices them, collects them for a batching window, matches them to drivers, makes offers and handles accept, reject, timeout and cancel.
 * <p>A <b>batching window</b> is a short wait (W milliseconds of scheduler time) during which new requests are collected, then assigned together. With W = 0 every request is matched the moment it arrives (greedy-immediate). {@link #dispatchPending} always matches at once and ignores the window.
 * <p>Error contract: a duplicate request id gives DUPLICATE_REQUEST, an unknown id UNKNOWN_REQUEST, an offer id this dispatcher did not issue or that is no longer live gives STALE_OFFER. Every check that can throw runs before anything is stored, so a refused call writes nothing (a request whose first match attempt throws is forgotten again).
 * <p>Flow: submit, quote locked, PENDING, window flush, OFFERED (timer started), then accept (ASSIGNED) or reject/timeout (the driver is added to that request's declined set and the request goes back to PENDING). A request ends as ASSIGNED, CANCELLED or EXPIRED (no driver within {@code maxWaitMs}); finished requests leave the active table so later scans stay small.
 * <p>Concurrency: every public method is synchronized on the dispatcher; lock order is dispatcher then fleet, never the reverse. Candidates are gathered under the fleet lock, then scored and assigned without it.
 */
public final class Dispatcher {

    /** Where a request is in its life. */
    public enum Status { PENDING, OFFERED, ASSIGNED, CANCELLED, EXPIRED }

    /** Everything we remember about one request. */
    private static final class Tracked {
        final Request request;
        final Quote quote;
        final Set<Long> declined = new HashSet<>();
        Status status = Status.PENDING;
        long driverId = -1;
        long offerId = -1;
        Scheduler.Cancellable timer;
        Scheduler.Cancellable expiry;

        /** Starts tracking a request with its locked quote. */
        Tracked(Request request, Quote quote) {
            this.request = request;
            this.quote = quote;
        }
    }

    private final Fleet fleet;
    private final Scheduler scheduler;
    private final PricingPolicy pricing;
    private final ScoringStrategy scoring;
    private final BatchAssigner assigner;
    private final long windowMs;
    private final long offerTimeoutMs;
    private final long maxWaitMs;
    private final long baseFareMinor;
    private final Gap gap;
    private final Map<Long, Tracked> active = new LinkedHashMap<>();
    private final Map<Long, Tracked> finished = new LinkedHashMap<>();
    private final Map<Long, Long> offerToRequest = new LinkedHashMap<>();
    private boolean flushScheduled;

    /**
     * Builds a dispatcher over a (possibly shared) fleet. The fleet should read the same clock as the scheduler.
     *
     * @param fleet the drivers
     * @param scheduler virtual or real time
     * @param pricing the surge policy
     * @param scoring the pair cost
     * @param assigner greedy or optimal
     * @param windowMs batching window, 0 means match immediately
     * @param offerTimeoutMs how long a driver has to answer, at least 1
     * @param maxWaitMs how long a request may wait for any driver before it is EXPIRED, at least 1
     * @param baseFareMinor the base fare in minor units
     * @param gap test hook
     */
    Dispatcher(Fleet fleet, Scheduler scheduler, PricingPolicy pricing, ScoringStrategy scoring, BatchAssigner assigner, long windowMs, long offerTimeoutMs, long maxWaitMs, long baseFareMinor, Gap gap) {
        if (windowMs < 0 || offerTimeoutMs < 1 || maxWaitMs < 1 || baseFareMinor < 0) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "window " + windowMs + " timeout " + offerTimeoutMs + " maxWait " + maxWaitMs);
        }
        this.fleet = Objects.requireNonNull(fleet);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.pricing = Objects.requireNonNull(pricing);
        this.scoring = Objects.requireNonNull(scoring);
        this.assigner = Objects.requireNonNull(assigner);
        this.windowMs = windowMs;
        this.offerTimeoutMs = offerTimeoutMs;
        this.maxWaitMs = maxWaitMs;
        this.baseFareMinor = baseFareMinor;
        this.gap = Objects.requireNonNull(gap);
    }

    /**
     * Builds a dispatcher with no test hook and a very long maximum wait.
     *
     * @param fleet the drivers
     * @param scheduler virtual or real time
     * @param pricing the surge policy
     * @param scoring the pair cost
     * @param assigner greedy or optimal
     * @param windowMs batching window, 0 means match immediately
     * @param offerTimeoutMs how long a driver has to answer
     * @param baseFareMinor the base fare in minor units
     */
    public Dispatcher(Fleet fleet, Scheduler scheduler, PricingPolicy pricing, ScoringStrategy scoring, BatchAssigner assigner, long windowMs, long offerTimeoutMs, long baseFareMinor) {
        this(fleet, scheduler, pricing, scoring, assigner, windowMs, offerTimeoutMs, Long.MAX_VALUE / 4, baseFareMinor, Gap.NONE);
    }

    /**
     * Accepts a request, locks its price now, and queues it for the next window (or matches at once when the window is 0). If the first match attempt throws (for example BAD_COST), the request is forgotten so the caller can retry.
     *
     * @param r the request
     * @return the locked quote
     */
    public synchronized Quote submit(Request r) {
        Objects.requireNonNull(r);
        if (active.containsKey(r.id()) || finished.containsKey(r.id())) {
            throw new DispatchException(DispatchException.Code.DUPLICATE_REQUEST, "request " + r.id());
        }
        Cell here = fleet.withIndex(ix -> ix.cellOf(r.pickup()));
        int open = 1;
        for (Tracked t : active.values()) {
            if (fleet.withIndex(ix -> ix.cellOf(t.request.pickup())).equals(here)) {
                open++;
            }
        }
        int available = fleet.withIndex(ix -> ix.countInCellOf(r.pickup(), id -> true));
        int bps = pricing.surgeBps(open, available);
        Quote q = new Quote(r.id(), bps, pricing.price(baseFareMinor, bps));
        Tracked t = new Tracked(r, q);
        active.put(r.id(), t);
        t.expiry = scheduler.schedule(maxWaitMs, () -> onExpiry(r.id()));
        try {
            if (windowMs == 0) {
                flush();
            } else {
                ensureFlush();
            }
        } catch (RuntimeException e) {
            t.expiry.cancel();
            active.remove(r.id());
            throw e;
        }
        return q;
    }

    /** Schedules one flush a window from now unless one is already waiting. */
    private void ensureFlush() {
        if (!flushScheduled) {
            flushScheduled = true;
            scheduler.schedule(windowMs, this::flushFromTimer);
        }
    }

    /** Entry point for the scheduler. The waiting flag is cleared first, so even if the flush throws, the next submit can schedule a new one. */
    private synchronized void flushFromTimer() {
        flushScheduled = false;
        flush();
    }

    /** Matches everything still pending right now, ignoring the window. Call it when new supply appears (a driver came online or finished a trip). */
    public synchronized void dispatchPending() {
        flush();
    }

    /** Matches up to one full batch of pending requests, offers the pairs, and schedules another flush if anything is still waiting. */
    private void flush() {
        List<Request> batch = new ArrayList<>();
        for (Tracked t : active.values()) {
            if (t.status == Status.PENDING && batch.size() < BatchMatcher.MAX_BATCH) {
                batch.add(t.request);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        java.util.function.BiPredicate<Request, Long> allowed = (rq, d) -> !active.get(rq.id()).declined.contains(d);
        BatchMatcher.Candidates cands = fleet.withIndex(ix -> BatchMatcher.gather(batch, ix, fleet::info, allowed));
        Map<Long, Long> picks = BatchMatcher.solve(batch, cands, allowed, scoring, assigner);
        gap.at("picked");
        boolean lost = false;
        for (Map.Entry<Long, Long> e : picks.entrySet()) {
            Tracked t = active.get(e.getKey());
            Optional<Offer> o = fleet.tryOffer(e.getValue(), t.request.id(), Math.addExact(scheduler.nowMs(), offerTimeoutMs));
            if (o.isPresent()) {
                t.status = Status.OFFERED;
                t.driverId = e.getValue();
                t.offerId = o.get().id();
                offerToRequest.put(o.get().id(), t.request.id());
                armTimer(t, o.get().id(), offerTimeoutMs);
            } else {
                lost = true;
            }
        }
        if (lost && windowMs == 0) {
            // another dispatcher took a picked driver first; it has left the grid, so matching again makes progress
            flush();
            return;
        }
        if (windowMs > 0 && active.values().stream().anyMatch(t -> t.status == Status.PENDING)) {
            ensureFlush();
        }
    }

    /** Starts (or restarts) the answer timer of an offer. */
    private void armTimer(Tracked t, long offerId, long delayMs) {
        t.timer = scheduler.schedule(delayMs, () -> onTimeout(offerId));
    }

    /** The offer timer fired. If the fleet clock says there is still time left (the timer fired early), re-arm for the rest; otherwise free the driver and send the request back to pending. */
    private synchronized void onTimeout(long offerId) {
        long left = fleet.remainingMs(offerId);
        if (left < 0 || !offerToRequest.containsKey(offerId)) {
            return;
        }
        if (left > 0) {
            armTimer(active.get(offerToRequest.get(offerId)), offerId, left);
            return;
        }
        fleet.expire(offerId).ifPresent(this::returnToPending);
    }

    /** The maximum wait passed: a request that never got a driver is finished as EXPIRED. */
    private synchronized void onExpiry(long requestId) {
        Tracked t = active.get(requestId);
        if (t != null && t.status == Status.PENDING) {
            finish(t, Status.EXPIRED);
        }
    }

    /** Moves a request to a terminal state, cancels its timers and removes it from the active table so scans stay small. */
    private void finish(Tracked t, Status s) {
        t.status = s;
        if (t.timer != null) {
            t.timer.cancel();
            t.timer = null;
        }
        if (t.expiry != null) {
            t.expiry.cancel();
            t.expiry = null;
        }
        active.remove(t.request.id());
        finished.put(t.request.id(), t);
    }

    /** Puts a declined or timed-out request back in the queue, remembering the driver so it is not offered again. */
    private void returnToPending(Offer o) {
        Long rid = offerToRequest.remove(o.id());
        Tracked t = active.get(rid);
        t.declined.add(o.driverId());
        t.status = Status.PENDING;
        t.driverId = -1;
        t.offerId = -1;
        if (t.timer != null) {
            t.timer.cancel();
            t.timer = null;
        }
        if (windowMs == 0) {
            flush();
        } else {
            ensureFlush();
        }
    }

    /** Looks up the request behind an offer this dispatcher issued, or throws STALE_OFFER before the fleet is touched. */
    private Tracked trackedByOffer(long offerId) {
        Long rid = offerToRequest.get(offerId);
        if (rid == null) {
            throw new DispatchException(DispatchException.Code.STALE_OFFER, "offer " + offerId + " is not live here");
        }
        return active.get(rid);
    }

    /**
     * The driver accepted. Refused with STALE_OFFER if the offer is not one of this dispatcher's live offers, timed out, was rejected, or was already accepted; a refused accept changes nothing, in this dispatcher or in the fleet.
     *
     * @param offerId the offer id
     */
    public synchronized void accept(long offerId) {
        Tracked t = trackedByOffer(offerId);
        fleet.accept(offerId);
        offerToRequest.remove(offerId);
        finish(t, Status.ASSIGNED);
    }

    /**
     * The driver declined: the driver is excluded for this request and the request is dispatched again. Refused with STALE_OFFER if the offer is not live here.
     *
     * @param offerId the offer id
     */
    public synchronized void reject(long offerId) {
        trackedByOffer(offerId);
        returnToPending(fleet.reject(offerId));
    }

    /**
     * The rider cancelled. A pending request is dropped; an offered one is withdrawn and its driver goes back to AVAILABLE. An assigned request cannot be cancelled here (the trip is under way).
     *
     * @param requestId the request
     */
    public synchronized void cancel(long requestId) {
        Tracked t = active.get(requestId);
        if (t == null) {
            if (finished.containsKey(requestId)) {
                throw new DispatchException(DispatchException.Code.ILLEGAL_TRANSITION, "request " + requestId + " already " + finished.get(requestId).status);
            }
            throw new DispatchException(DispatchException.Code.UNKNOWN_REQUEST, "request " + requestId);
        }
        if (t.status == Status.OFFERED) {
            fleet.release(t.offerId);
            offerToRequest.remove(t.offerId);
        }
        finish(t, Status.CANCELLED);
    }

    /** Returns how many requests are still PENDING or OFFERED. */
    public synchronized int activeCount() {
        return active.size();
    }

    /** Returns the locked quote of a request or throws UNKNOWN_REQUEST. */
    public synchronized Quote quote(long requestId) {
        return tracked(requestId).quote;
    }

    /** Returns where a request is in its life or throws UNKNOWN_REQUEST. */
    public synchronized Status status(long requestId) {
        return tracked(requestId).status;
    }

    /** Returns the driver currently offered or assigned, or -1 when none. */
    public synchronized long driverOf(long requestId) {
        return tracked(requestId).driverId;
    }

    /** Returns the live offer id of a request, or -1 when none. */
    public synchronized long offerOf(long requestId) {
        return tracked(requestId).offerId;
    }

    /** Looks up a tracked request in either table or throws UNKNOWN_REQUEST. */
    private Tracked tracked(long id) {
        Tracked t = active.get(id);
        if (t == null) {
            t = finished.get(id);
        }
        if (t == null) {
            throw new DispatchException(DispatchException.Code.UNKNOWN_REQUEST, "request " + id);
        }
        return t;
    }
}
