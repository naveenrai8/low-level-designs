package com.lld.ticketing;

import java.util.HashSet;
import java.util.List;

/**
 * What a buyer asks for: these seats of this show, for this total price.
 *
 * @param buyerId who pays and who gets the notification
 * @param showId the show
 * @param seatIds the seats, at least one, no repeats (stored sorted, so the same seats in another order are the same request)
 * @param amount the total price in minor units (cents), positive
 */
public record CheckoutRequest(String buyerId, String showId, List<String> seatIds, long amount) {

    /** Sorts a copy of the seat list and rejects blank ids, an empty or repeating seat list and a price of zero or less, which are caller bugs. */
    public CheckoutRequest {
        if (buyerId == null || buyerId.isBlank() || showId == null || showId.isBlank()) {
            throw new IllegalArgumentException("buyerId and showId are required");
        }
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("at least one seat is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new IllegalArgumentException("seats must not repeat");
        }
        seatIds = seatIds.stream().sorted().toList();
    }
}
