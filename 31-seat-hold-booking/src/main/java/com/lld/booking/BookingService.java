package com.lld.booking;

import java.util.List;
import java.util.Set;

/**
 * Hold-then-confirm booking of numbered units (cinema seats, concert seats, flight seats, hotel rooms of one date). A *show* is one inventory: a screening, a flight, a hotel date.
 *
 * <p>A seat is AVAILABLE, then HELD by one hold for a short time, then BOOKED. A hold that is released or expires puts its seats back to AVAILABLE. A hold is valid while {@code now < expiresAt}, so it is expired AT {@code expiresAt} (half-open, as in Topic 10).
 *
 * <p>Error contract (all unchecked {@link BookingException}s unless noted):
 * <ul>
 * <li>{@link SeatUnavailableException}: some requested seats are held or sold. Lists all of them. Nothing was held.
 * <li>{@link UnknownSeatException} / {@link ShowNotFoundException}: the request names something that does not exist.
 * <li>{@link HoldExpiredException}: the hold ran out. On confirm the payment is recorded as needing a refund.
 * <li>{@link HoldNotFoundException}: no such hold (never issued, released, or forgotten after the retention time).
 * <li>{@link AlreadyConfirmedException}: the hold already became a booking under another idempotency key.
 * <li>{@link IdempotencyConflictException}: the key was used with a different hold or payment reference.
 * <li>{@link IllegalArgumentException}: an empty or duplicated seat list, a blank key. It is a caller bug, not a business case.
 * </ul>
 */
public interface BookingService {

    /**
     * Registers a show with the given numbered seats. The overbook policy may add extra sellable units named OVERBOOK-1, OVERBOOK-2 and so on. Throws IllegalArgumentException if the show exists, the seats are empty, or a seat id starts with OVERBOOK- (reserved).
     */
    void addShow(String showId, Set<String> seatIds);

    /**
     * Holds all the given seats for the configured hold time, or none of them. Returns the hold with its expiry instant.
     */
    Hold hold(String showId, List<String> seatIds);

    /**
     * Gives the hold's seats back at once. Releasing a hold that is already released or expired does nothing, so a retried DELETE is safe.
     */
    void release(String holdId);

    /**
     * Turns a live hold into a booking after payment. The same key and the same request return the same booking (replay is true), and a different request under the same key is a conflict. An expired hold fails and the payment is recorded as needing a refund; the same request retried gets the same HoldExpiredException and no second note, and a different request under that key is a conflict. Other failures (released, already booked) record no note: refunding those is the payment layer's job.
     */
    ConfirmResult confirm(String holdId, String idempotencyKey, String paymentRef);

    /**
     * Returns what a caller should see for one seat right now. An expired hold reads as AVAILABLE.
     */
    SeatState seatState(String showId, String seatId);

    /**
     * Returns the seats that could be held right now, sorted.
     */
    List<String> availableSeats(String showId);

    /**
     * Cleans up: frees the seats of expired holds and forgets old holds and old idempotency keys. It never changes the answer of any other method, it only saves memory. Returns how many things were dropped.
     */
    int sweep();

    /**
     * Returns the payments that arrived after their hold expired and still need a refund, in the order they were noticed.
     */
    List<RefundNeeded> refundsNeeded();

    /**
     * Marks a refund as paid, so it leaves the list. The note is kept internally so a retried confirm cannot create it again. Does nothing for an unknown key.
     */
    void refundDone(String idempotencyKey);
}
