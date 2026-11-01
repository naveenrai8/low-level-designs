package com.lld.booking;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The in-memory {@link BookingService}.
 *
 * <p>Concurrency model: one {@code ReentrantLock} per seat. Every operation that touches several seats takes their locks in sorted seat-id order (Topic 08), so two callers can never wait for each other in a circle. Callers working on different seats of the same show do not block each other. Hold, release and confirm each lock exactly the seats of one hold.
 *
 * <p>Lazy expiry: nothing runs on a timer. A seat whose hold has reached its expiry instant is simply treated as free the next time anyone looks at it, and the next hold overwrites it. {@link #sweep()} only tidies memory.
 */
public final class SeatHoldBookingService implements BookingService {

    /** Everything we know about one hold. {@code status} is read and written only while the hold's seat locks are held, so it needs no volatile. */
    private static final class HoldRecord {
        /** Where a hold is in its life: waiting, turned into a booking, or given back. */
        enum Status { HELD, CONFIRMED, RELEASED }

        final Hold hold;
        Status status = Status.HELD;

        /** Wraps the public hold. */
        HoldRecord(Hold hold) {
            this.hold = hold;
        }
    }

    /** The seats of one show. The map never changes after the show is added. */
    private record Show(Map<String, SeatCell> seats) {
    }

    private static final int PURGE_EVERY = 256;
    private static final int KEY_STRIPES = 64;
    private static final String OVERBOOK_PREFIX = "OVERBOOK-";

    /** A refund note and whether the refund was paid. Done notes stay (as tombstones) so a retry cannot create the note again. */
    private static final class RefundNote {
        final RefundNeeded note;
        boolean done;

        /** Wraps a new, unpaid note. */
        RefundNote(RefundNeeded note) {
            this.note = note;
        }
    }

    private final Clock clock;
    private final BookingConfig config;
    private final Supplier<String> holdIds;
    private final Supplier<String> bookingIds;
    private final OverbookPolicy overbook;
    private final LockOrder lockOrder;
    private final Gap gap;
    private final Map<String, Show> shows = new ConcurrentHashMap<>();
    private final Map<String, HoldRecord> holds = new ConcurrentHashMap<>();
    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, RefundNote> refunds = new LinkedHashMap<>();
    private final ReentrantLock[] keyLocks = new ReentrantLock[KEY_STRIPES];
    private final AtomicInteger holdCalls = new AtomicInteger();

    /** Creates a service with no overbooking. */
    public SeatHoldBookingService(Clock clock, BookingConfig config, Supplier<String> holdIds, Supplier<String> bookingIds) {
        this(clock, config, holdIds, bookingIds, OverbookPolicy.NONE);
    }

    /** Creates a service with the given overbook policy. */
    public SeatHoldBookingService(Clock clock, BookingConfig config, Supplier<String> holdIds, Supplier<String> bookingIds, OverbookPolicy overbook) {
        this(clock, config, holdIds, bookingIds, overbook, LockOrder.SORTED, Gap.NONE);
    }

    /** Full constructor for tests: choose the lock order (to show the deadlock) and a gap hook (to force interleavings). */
    SeatHoldBookingService(Clock clock, BookingConfig config, Supplier<String> holdIds, Supplier<String> bookingIds, OverbookPolicy overbook, LockOrder lockOrder, Gap gap) {
        this.clock = clock;
        this.config = config;
        this.holdIds = holdIds;
        this.bookingIds = bookingIds;
        this.overbook = overbook;
        this.lockOrder = lockOrder;
        this.gap = gap;
        for (int i = 0; i < KEY_STRIPES; i++) {
            keyLocks[i] = new ReentrantLock();
        }
    }

    /** Registers the show, with one lock-guarded cell per seat plus any overbook units the policy allows. */
    @Override
    public void addShow(String showId, Set<String> seatIds) {
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("a show needs at least one seat");
        }
        Map<String, SeatCell> cells = new TreeMap<>();
        for (String id : seatIds) {
            if (id.startsWith(OVERBOOK_PREFIX)) {
                throw new IllegalArgumentException("seat ids must not start with " + OVERBOOK_PREFIX + " (reserved for overbook units): " + id);
            }
            cells.put(id, new SeatCell(id));
        }
        int extra = overbook.sellable(seatIds.size()) - seatIds.size();
        if (extra < 0) {
            throw new IllegalArgumentException("overbook policy must not sell fewer units than the capacity");
        }
        for (int i = 1; i <= extra; i++) {
            cells.put(OVERBOOK_PREFIX + i, new SeatCell(OVERBOOK_PREFIX + i));
        }
        if (shows.putIfAbsent(showId, new Show(cells)) != null) {
            throw new IllegalArgumentException("show already exists: " + showId);
        }
    }

    /** Validates the request, locks the seats in sorted order, then holds all of them or throws with every blocking seat. Expired holds are overwritten on the spot (lazy expiry). */
    @Override
    public Hold hold(String showId, List<String> seatIds) {
        Show show = show(showId);
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("seat list must not be empty");
        }
        Set<String> distinct = new TreeSet<>(seatIds);
        if (distinct.size() != seatIds.size()) {
            throw new IllegalArgumentException("seat list has duplicates: " + seatIds);
        }
        Set<String> unknown = new TreeSet<>();
        for (String id : seatIds) {
            if (!show.seats().containsKey(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            throw new UnknownSeatException(showId, unknown);
        }
        maybePurgeHolds();
        List<SeatCell> order = new ArrayList<>();
        for (String id : lockOrder == LockOrder.SORTED ? distinct : seatIds) {
            order.add(show.seats().get(id));
        }
        gap.at(Gap.Phase.BEFORE_LOCKING, null);
        List<SeatCell> locked = lockAll(order);
        try {
            Instant now = clock.instant();
            Set<String> blocked = new TreeSet<>();
            for (SeatCell c : order) {
                if (!c.isFree(now)) {
                    blocked.add(c.id);
                }
            }
            if (!blocked.isEmpty()) {
                throw new SeatUnavailableException(showId, blocked);
            }
            Instant expiresAt = now.plus(config.holdTtl());
            String holdId = holdIds.get();
            for (SeatCell c : order) {
                c.state = SeatState.HELD;
                c.holdId = holdId;
                c.expiresAt = expiresAt;
            }
            Hold hold = new Hold(holdId, showId, new ArrayList<>(distinct), expiresAt);
            holds.put(holdId, new HoldRecord(hold));
            return hold;
        } finally {
            unlockAll(locked);
        }
    }

    /** Frees the hold's seats under their locks. Only seats still owned by this hold are reset, so a stale id cannot free a new owner's seats. */
    @Override
    public void release(String holdId) {
        HoldRecord rec = findHold(holdId, clock.instant());
        List<SeatCell> locked = lockAll(cellsOf(rec));
        try {
            switch (rec.status) {
                case CONFIRMED -> throw new AlreadyConfirmedException(holdId);
                case RELEASED -> { }
                case HELD -> {
                    for (SeatCell c : cellsOf(rec)) {
                        if (holdId.equals(c.holdId)) {
                            c.reset();
                        }
                    }
                    rec.status = HoldRecord.Status.RELEASED;
                }
            }
        } finally {
            unlockAll(locked);
        }
    }

    /** Takes the key's lock first, so two confirms with the same key run one after the other. Under it: replay a known outcome, else lock the seats and either book, or record the expired outcome and a refund note. A seat no longer owned by this hold (for example after the clock stepped backwards) counts as lost, never as bookable. */
    @Override
    public ConfirmResult confirm(String holdId, String idempotencyKey, String paymentRef) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        IdempotencyStore.Fingerprint fingerprint = new IdempotencyStore.Fingerprint(holdId, paymentRef);
        ReentrantLock keyLock = keyLocks[Math.floorMod(idempotencyKey.hashCode(), KEY_STRIPES)];
        keyLock.lock();
        try {
            var known = idempotency.lookup(idempotencyKey, fingerprint, clock.instant());
            if (known.isPresent()) {
                return replay(known.get());
            }
            gap.at(Gap.Phase.AFTER_KEY_LOOKUP, null);
            HoldRecord rec = findHold(holdId, clock.instant());
            List<SeatCell> locked = lockAll(cellsOf(rec));
            try {
                return confirmLocked(rec, idempotencyKey, fingerprint, paymentRef);
            } finally {
                unlockAll(locked);
            }
        } finally {
            keyLock.unlock();
        }
    }

    /** Turns a stored outcome back into an answer: the same booking, or the same HoldExpiredException without a second refund note. */
    private static ConfirmResult replay(IdempotencyStore.Outcome outcome) {
        if (outcome instanceof IdempotencyStore.Booked b) {
            return new ConfirmResult(b.booking(), true);
        }
        throw new HoldExpiredException(((IdempotencyStore.Expired) outcome).holdId());
    }

    /** The part of confirm that needs the seat locks and the key lock. */
    private ConfirmResult confirmLocked(HoldRecord rec, String key, IdempotencyStore.Fingerprint fingerprint, String paymentRef) {
        String holdId = rec.hold.holdId();
        Instant now = clock.instant();
        if (rec.status == HoldRecord.Status.RELEASED) {
            throw new HoldNotFoundException(holdId);
        }
        if (rec.status == HoldRecord.Status.CONFIRMED) {
            throw new AlreadyConfirmedException(holdId);
        }
        if (!rec.hold.isLiveAt(now) || !ownsAllSeats(rec)) {
            noteRefund(key, holdId, paymentRef, now);
            idempotency.put(key, fingerprint, new IdempotencyStore.Expired(holdId), now.plus(config.idempotencyTtl()));
            throw new HoldExpiredException(holdId);
        }
        String bookingId = bookingIds.get();
        for (SeatCell c : cellsOf(rec)) {
            c.state = SeatState.BOOKED;
            c.expiresAt = null;
        }
        rec.status = HoldRecord.Status.CONFIRMED;
        Booking booking = new Booking(bookingId, holdId, rec.hold.showId(), rec.hold.seatIds(), paymentRef, now);
        idempotency.put(key, fingerprint, new IdempotencyStore.Booked(booking), now.plus(config.idempotencyTtl()));
        return new ConfirmResult(booking, false);
    }

    /** Says whether every seat of the hold is still HELD by this very hold. It is false if another hold took a seat after this one expired. Caller holds the seat locks. */
    private boolean ownsAllSeats(HoldRecord rec) {
        for (SeatCell c : cellsOf(rec)) {
            if (c.state != SeatState.HELD || !rec.hold.holdId().equals(c.holdId)) {
                return false;
            }
        }
        return true;
    }

    /** Writes one refund note per idempotency key. An existing note, paid or not, is kept, so retries and a paid refund cannot create a second one. */
    private void noteRefund(String key, String holdId, String paymentRef, Instant now) {
        synchronized (refunds) {
            refunds.computeIfAbsent(key, k -> new RefundNote(new RefundNeeded(k, holdId, paymentRef, now)));
        }
    }

    /** Reads one seat under its lock and applies the half-open expiry rule. */
    @Override
    public SeatState seatState(String showId, String seatId) {
        SeatCell c = show(showId).seats().get(seatId);
        if (c == null) {
            throw new UnknownSeatException(showId, Set.of(seatId));
        }
        c.lock.lock();
        try {
            return c.visibleState(clock.instant());
        } finally {
            c.lock.unlock();
        }
    }

    /** Lists the seats that are free right now, in sorted order. */
    @Override
    public List<String> availableSeats(String showId) {
        List<String> out = new ArrayList<>();
        for (SeatCell c : show(showId).seats().values()) {
            if (seatState(showId, c.id) == SeatState.AVAILABLE) {
                out.add(c.id);
            }
        }
        return out;
    }

    /** Resets expired holds and drops old hold records and idempotency keys. Answers never change because every read already applies the same expiry rules. */
    @Override
    public int sweep() {
        Instant now = clock.instant();
        int dropped = 0;
        for (Show show : shows.values()) {
            for (SeatCell c : show.seats().values()) {
                c.lock.lock();
                try {
                    if (c.state == SeatState.HELD && c.isFree(now)) {
                        c.reset();
                        dropped++;
                    }
                } finally {
                    c.lock.unlock();
                }
            }
        }
        dropped += purgeHolds(now);
        dropped += idempotency.purge(now);
        return dropped;
    }

    /** Returns the unpaid refund notes, oldest first. */
    @Override
    public List<RefundNeeded> refundsNeeded() {
        synchronized (refunds) {
            List<RefundNeeded> out = new ArrayList<>();
            for (RefundNote n : refunds.values()) {
                if (!n.done) {
                    out.add(n.note);
                }
            }
            return out;
        }
    }

    /** Marks the refund as paid. The note stays as a tombstone so a retried confirm cannot create it again. */
    @Override
    public void refundDone(String idempotencyKey) {
        synchronized (refunds) {
            RefundNote n = refunds.get(idempotencyKey);
            if (n != null) {
                n.done = true;
            }
        }
    }

    /** Returns how many idempotency keys are remembered (tests use it to prove the memory bound). */
    int idempotencyKeys() {
        return idempotency.size();
    }

    /** Says whether a seat's lock is held by some thread (tests use it to prove locks are released). */
    boolean seatLocked(String showId, String seatId) {
        return show(showId).seats().get(seatId).lock.isLocked();
    }

    /** Returns how many hold records are remembered (tests use it to prove the memory bound). */
    int holdRecords() {
        return holds.size();
    }

    /** Looks the show up or throws ShowNotFoundException. */
    private Show show(String showId) {
        Show s = shows.get(showId);
        if (s == null) {
            throw new ShowNotFoundException(showId);
        }
        return s;
    }

    /** Finds a hold record. A hold older than expiry plus the retention time is forgotten and answers "not found", exactly as if the sweep had removed it, so sweeping never changes an answer. */
    private HoldRecord findHold(String holdId, Instant now) {
        HoldRecord rec = holds.get(holdId);
        if (rec == null || !now.isBefore(rec.hold.expiresAt().plus(config.expiredHoldRetention()))) {
            throw new HoldNotFoundException(holdId);
        }
        return rec;
    }

    /** Returns the seat cells of a hold in sorted order. */
    private List<SeatCell> cellsOf(HoldRecord rec) {
        Show show = shows.get(rec.hold.showId());
        List<SeatCell> out = new ArrayList<>();
        for (String id : rec.hold.seatIds()) {
            out.add(show.seats().get(id));
        }
        return out;
    }

    /**
     * Takes the locks in the given order and returns them. If anything goes wrong half way (an interrupt, which is the only way out of a deadlock, or a throwing test hook), the locks already taken are released first. An interrupt restores the flag and throws IllegalStateException.
     */
    private List<SeatCell> lockAll(Collection<SeatCell> cells) {
        List<SeatCell> taken = new ArrayList<>();
        boolean ok = false;
        try {
            for (SeatCell c : cells) {
                c.lock.lockInterruptibly();
                taken.add(c);
                if (taken.size() == 1) {
                    gap.at(Gap.Phase.AFTER_FIRST_LOCK, c.id);
                }
            }
            ok = true;
            return taken;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while locking seats", e);
        } finally {
            if (!ok) {
                unlockAll(taken);
            }
        }
    }

    /** Releases the locks in reverse order of taking. */
    private static void unlockAll(List<SeatCell> taken) {
        for (int i = taken.size() - 1; i >= 0; i--) {
            taken.get(i).lock.unlock();
        }
    }

    /** Every 256th hold call, forgets old hold records so memory stays bounded even if nobody calls sweep. */
    private void maybePurgeHolds() {
        if (holdCalls.incrementAndGet() % PURGE_EVERY == 0) {
            purgeHolds(clock.instant());
            idempotency.purge(clock.instant());
        }
    }

    /** Removes hold records past expiry plus retention. Returns how many. */
    private int purgeHolds(Instant now) {
        int before = holds.size();
        holds.values().removeIf(r -> !now.isBefore(r.hold.expiresAt().plus(config.expiredHoldRetention())));
        return before - holds.size();
    }
}
