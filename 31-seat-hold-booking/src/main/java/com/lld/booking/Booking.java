package com.lld.booking;

import java.time.Instant;
import java.util.List;

/**
 * A confirmed sale. It is the answer to a confirm request and is returned again, unchanged, on every replay of the same idempotency key.
 *
 * @param bookingId the id from the injected id supplier
 * @param holdId the hold that was turned into this booking
 * @param showId the show the seats belong to
 * @param seatIds the sold seats, sorted
 * @param paymentRef the caller's payment reference (for example a gateway charge id)
 * @param confirmedAt when the booking was made
 */
public record Booking(String bookingId, String holdId, String showId, List<String> seatIds, String paymentRef, Instant confirmedAt) {

    /** Copies the seat list so the record cannot be changed from outside. */
    public Booking {
        seatIds = List.copyOf(seatIds);
    }
}
