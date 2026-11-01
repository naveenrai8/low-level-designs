package com.lld.ticketing;

import com.lld.booking.AlreadyConfirmedException;
import com.lld.booking.Booking;
import com.lld.booking.BookingException;
import com.lld.booking.BookingService;
import com.lld.booking.HoldExpiredException;
import com.lld.booking.HoldNotFoundException;
import com.lld.ledger.Entry;
import com.lld.ledger.HoldService;
import com.lld.ledger.HoldState;
import com.lld.ledger.Ledger;
import com.lld.ledger.LedgerException;
import com.lld.notify.ChannelType;
import com.lld.notify.InvalidRequestException;
import com.lld.notify.NotificationRequest;
import com.lld.notify.NotificationService;
import com.lld.notify.Priority;
import com.lld.notify.SubmitResult;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The glue of Topics 31 (seat holds), 32 (ledger holds) and 44 (notifications) as a small saga with one record per checkout.
 *
 * <p>The steps are NEW, SEATS_HELD, PAYMENT_HELD, CAPTURED, CONFIRMED. Each step does one thing, then writes down that it did it, then moves on. A retry with the same key reads the record and continues from the next step, so no step runs its effect twice (and the steps that cannot be written down atomically are idempotent in their own module).
 *
 * <p>Rules: (1) roll forward while the seat hold is live, because the buyer already paid or reserved; (2) when the seat hold has run out before the capture, roll back: void the payment hold and release the seats; (3) when the money was captured but the confirm fails, refund: reverse the capture. A confirm is never "forced".
 *
 * <p>The confirmation message is an outbox: the "notify later" note is written in the same step that records the booking. Sending happens afterwards and its failure never touches the booking. The message key is derived from the checkout key, so a retry cannot send twice.
 *
 * <p>Concurrency: each record has its own {@code ReentrantLock}, taken with {@code tryLock}. A second call with a key whose first call is still running does not wait: it gets {@link CheckoutInProgressException} (409). {@code sweep}, {@code flushOutbox} and {@code purge} skip busy records instead of waiting, so one slow checkout cannot stall them. A lock (not {@code synchronized}) also avoids pinning a virtual thread on JDK 21. Different checkouts run in parallel, and the seat locks of Topic 31 decide who gets a seat.
 *
 * <p>Cost of one happy checkout: 6 calls into the three modules (hold, authorize, holdState, capture, confirm, submit) and 1 record lock, plus the locks those modules take inside (one per seat, the wallet and venue accounts, one key stripe).
 */
public final class CheckoutService implements Checkout {

    /** The template name the confirmation uses; the caller registers it in the notification service. */
    public static final String TEMPLATE = "ticket-confirmed";

    private final Clock clock;
    private final BookingService bookings;
    private final Ledger ledger;
    private final HoldService holds;
    private final NotificationService notifications;
    private final TicketingConfig config;
    private final CrashHook hook;
    private final ConcurrentHashMap<String, CheckoutRecord> records = new ConcurrentHashMap<>();
    private final AtomicInteger sweepFailures = new AtomicInteger();

    /**
     * Wires the checkout to the three modules it reuses. Every outside thing is passed in, so tests control time and failures.
     *
     * @param holds the hold side of the ledger (usually the same object as {@code ledger})
     * @param hook where a test may simulate a crash; use {@link CrashHook#NONE} in production
     */
    public CheckoutService(Clock clock, BookingService bookings, Ledger ledger, HoldService holds, NotificationService notifications, TicketingConfig config, CrashHook hook) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bookings = Objects.requireNonNull(bookings, "bookings");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.config = Objects.requireNonNull(config, "config");
        this.hook = Objects.requireNonNull(hook, "hook");
    }

    /** The ledger account a buyer pays from. The caller opens it; the name is a convention so the checkout needs no account lookup. */
    public static String walletOf(String buyerId) {
        return "wallet:" + buyerId;
    }

    /** Finds or creates the record, refuses a different request or a busy record, then (under the record lock) runs the saga to an end state and flushes the outbox. A finished checkout does no work and answers replay true. */
    @Override
    public CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        CheckoutRecord rec = records.computeIfAbsent(idempotencyKey, k -> new CheckoutRecord(k, request));
        if (!rec.request.equals(request)) {
            throw new CheckoutKeyConflictException(idempotencyKey);
        }
        if (!rec.lock.tryLock()) {
            throw new CheckoutInProgressException(idempotencyKey);
        }
        try {
            boolean replay = rec.state.isTerminal();
            run(rec);
            notifyBuyer(rec);
            return answer(rec, replay);
        } finally {
            rec.lock.unlock();
        }
    }

    /** Resumes every unfinished checkout whose seat hold has run out. A live checkout is left alone (its own caller will retry), a busy one is skipped, and one that fails is counted and skipped so it cannot block the ones after it. */
    @Override
    public int sweep() {
        int resumed = 0;
        for (CheckoutRecord rec : records.values()) {
            if (!rec.lock.tryLock()) {
                continue;
            }
            try {
                if (!rec.state.isTerminal() && rec.hold != null && !live(rec)) {
                    run(rec);
                    resumed++;
                }
            } catch (RuntimeException e) {
                sweepFailures.incrementAndGet();
            } finally {
                rec.lock.unlock();
            }
        }
        return resumed;
    }

    /** Says how many times a sweep had to skip a record because it threw. A healthy system keeps this at 0; a rising number is an alert. */
    public int sweepFailures() {
        return sweepFailures.get();
    }

    /** Walks the records and tries to send the confirmation of each CONFIRMED one that is still waiting. Busy records are skipped. */
    @Override
    public int flushOutbox() {
        int handed = 0;
        for (CheckoutRecord rec : records.values()) {
            if (!rec.lock.tryLock()) {
                continue;
            }
            try {
                if (rec.state == State.CONFIRMED && rec.notifyPending) {
                    notifyBuyer(rec);
                    handed += rec.notifyPending ? 0 : 1;
                }
            } finally {
                rec.lock.unlock();
            }
        }
        return handed;
    }

    /** Forgets finished records that are older than the key retention and have no message waiting. Busy records are skipped. */
    @Override
    public int purge() {
        int gone = 0;
        for (CheckoutRecord rec : records.values()) {
            if (!rec.lock.tryLock()) {
                continue;
            }
            try {
                boolean old = rec.finishedAt != null && !clock.instant().isBefore(rec.finishedAt.plus(config.keyRetention()));
                if (rec.state.isTerminal() && !rec.notifyPending && old) {
                    records.remove(rec.key, rec);
                    gone++;
                }
            } finally {
                rec.lock.unlock();
            }
        }
        return gone;
    }

    /** Reads the state of one checkout without waiting (the state field is volatile). */
    @Override
    public State stateOf(String idempotencyKey) {
        CheckoutRecord rec = records.get(idempotencyKey);
        return rec == null ? null : rec.state;
    }

    /** Repeats single steps until the checkout reaches an end state. A thrown exception (a crash) leaves the record at the last completed step. */
    private void run(CheckoutRecord rec) {
        while (!rec.state.isTerminal()) {
            step(rec);
        }
        if (rec.finishedAt == null) {
            rec.finishedAt = clock.instant();
        }
    }

    /** Does exactly the next step for the record's current state. */
    private void step(CheckoutRecord rec) {
        switch (rec.state) {
            case NEW -> holdSeats(rec);
            case SEATS_HELD -> holdPayment(rec);
            case PAYMENT_HELD -> capture(rec);
            case CAPTURED -> confirm(rec);
            default -> throw new IllegalStateException("end state has no next step: " + rec.state);
        }
    }

    /** Step 1: holds the seats (Topic 31). If any seat is taken or unknown, nothing was held and the checkout is REJECTED. */
    private void holdSeats(CheckoutRecord rec) {
        hook.at(CrashHook.Point.BEFORE_SEAT_HOLD, rec.key);
        try {
            rec.hold = bookings.hold(rec.request.showId(), rec.request.seatIds());
        } catch (BookingException e) {
            rec.reason = e.getMessage();
            rec.state = State.REJECTED;
            return;
        }
        rec.state = State.SEATS_HELD;
        hook.at(CrashHook.Point.AFTER_SEAT_HOLD, rec.key);
    }

    /** Step 2: reserves the price on the buyer's wallet (Topic 32 authorize, money does not move). The reservation outlives the seat hold by the grace time. Not enough money means DECLINED and the seats go back. */
    private void holdPayment(CheckoutRecord rec) {
        if (!live(rec)) {
            compensate(rec, State.CANCELLED, "seat hold expired before the payment hold");
            return;
        }
        Duration ttl = Duration.between(clock.instant(), rec.hold.expiresAt()).plus(config.paymentGrace());
        try {
            String id = holds.authorize(walletOf(rec.request.buyerId()), rec.request.amount(), ttl);
            // Known gap: a crash here leaves a reservation the record does not know. A retry reserves again, and the first one frees itself at its TTL.
            hook.at(CrashHook.Point.AFTER_AUTHORIZE_BEFORE_SAVE, rec.key);
            rec.paymentHoldId = id;
        } catch (LedgerException e) {
            if (e.reason() != LedgerException.Reason.INSUFFICIENT_FUNDS) {
                throw e;
            }
            compensate(rec, State.DECLINED, "insufficient funds");
            return;
        }
        rec.state = State.PAYMENT_HELD;
        hook.at(CrashHook.Point.AFTER_PAYMENT_HOLD, rec.key);
    }

    /** Step 3: moves the reserved money to the venue. If the seat hold ran out first, it rolls back instead, unless the ledger says the money already moved (a crash between capture and the note), in which case it records the capture and lets the confirm decide. */
    private void capture(CheckoutRecord rec) {
        boolean alreadyCaptured = holds.holdState(rec.paymentHoldId) == HoldState.CAPTURED;
        if (!live(rec) && !alreadyCaptured) {
            compensate(rec, State.CANCELLED, "seat hold expired before the capture");
            return;
        }
        Entry entry = holds.capture(rec.paymentHoldId, rec.request.amount(), config.venueAccountId());
        hook.at(CrashHook.Point.AFTER_CAPTURE_BEFORE_SAVE, rec.key);
        rec.captureEntryId = entry.id();
        rec.state = State.CAPTURED;
        hook.at(CrashHook.Point.AFTER_CAPTURE, rec.key);
    }

    /** Step 4: confirms the booking (Topic 31, with the checkout key, so a repeat returns the same booking). If the hold is gone the money is refunded. On success the booking and the outbox note are written in one go. */
    private void confirm(CheckoutRecord rec) {
        Booking booking;
        try {
            booking = bookings.confirm(rec.hold.holdId(), rec.key, rec.captureEntryId).booking();
        } catch (HoldExpiredException e) {
            // Only this proves the booking was never made, so only this may refund.
            refund(rec, e.getMessage());
            return;
        } catch (HoldNotFoundException | AlreadyConfirmedException e) {
            // Topic 31 forgot the hold, or it was booked under another key. We cannot tell whether the seats are ours, so we leave the money alone and flag it.
            rec.reason = "needs review: " + e.getMessage();
            rec.state = State.NEEDS_REVIEW;
            return;
        }
        hook.at(CrashHook.Point.AFTER_CONFIRM_BEFORE_SAVE, rec.key);
        rec.bookingId = booking.bookingId();
        rec.seatsText = String.join(",", booking.seatIds());
        rec.notifyPending = true;
        rec.state = State.CONFIRMED;
        hook.at(CrashHook.Point.AFTER_CONFIRM, rec.key);
    }

    /** Rolls back before any money moved: voids the payment hold (already voided, expired or gone counts as done), gives the seats back (a hold Topic 31 already forgot counts as done), and ends in the given state. */
    private void compensate(CheckoutRecord rec, State end, String why) {
        if (rec.paymentHoldId != null) {
            try {
                holds.voidHold(rec.paymentHoldId);
            } catch (LedgerException e) {
                if (e.reason() != LedgerException.Reason.HOLD_NOT_ACTIVE && e.reason() != LedgerException.Reason.HOLD_EXPIRED) {
                    throw e;
                }
            }
            hook.at(CrashHook.Point.AFTER_VOID, rec.key);
        }
        if (rec.hold != null) {
            try {
                bookings.release(rec.hold.holdId());
            } catch (HoldNotFoundException e) {
                // Topic 31 forgot the hold after its retention, so the seats are free already.
            }
        }
        rec.reason = why;
        rec.state = end;
    }

    /** Undoes a capture whose confirm failed: appends the mirror entry to the ledger (already reversed counts as done) and closes the refund note Topic 31 wrote. */
    private void refund(CheckoutRecord rec, String why) {
        try {
            ledger.reverse(rec.captureEntryId);
        } catch (LedgerException e) {
            if (e.reason() != LedgerException.Reason.ALREADY_REVERSED) {
                throw e;
            }
        }
        hook.at(CrashHook.Point.AFTER_REVERSE, rec.key);
        bookings.refundDone(rec.key);
        rec.reason = why;
        rec.state = State.REFUNDED;
    }

    /** The outbox step: if a confirmation is waiting, hands it to the notification service under a key made from the checkout key. A rejected message stays waiting and never changes the booking. */
    private void notifyBuyer(CheckoutRecord rec) {
        if (rec.state != State.CONFIRMED || !rec.notifyPending) {
            return;
        }
        hook.at(CrashHook.Point.BEFORE_NOTIFY, rec.key);
        NotificationRequest message = new NotificationRequest(rec.request.buyerId(), ChannelType.EMAIL, TEMPLATE,
                Map.of("booking", rec.bookingId, "seats", rec.seatsText), Priority.NORMAL);
        SubmitResult submitted;
        try {
            submitted = notifications.submit("confirm:" + rec.key, message);
        } catch (InvalidRequestException e) {
            rec.reason = "notification waiting: " + e.getMessage();
            return;
        }
        rec.notificationId = submitted.id();
        rec.reason = "";
        hook.at(CrashHook.Point.AFTER_NOTIFY_SUBMIT, rec.key);
        rec.notifyPending = false;
    }

    /** Says whether the seat hold is still valid right now. */
    private boolean live(CheckoutRecord rec) {
        return rec.hold.isLiveAt(clock.instant());
    }

    /** Builds the public answer from the record. */
    private static CheckoutResult answer(CheckoutRecord rec, boolean replay) {
        return new CheckoutResult(rec.key, rec.state, rec.bookingId, rec.notificationId, rec.reason, replay);
    }
}
