package com.lld.ticketing;

/**
 * The answer to a checkout call.
 *
 * @param checkoutId the idempotency key the buyer sent
 * @param state where the checkout ended (always an end state when no exception was thrown)
 * @param bookingId the booking id when CONFIRMED, else null
 * @param notificationId the id of the confirmation message when it has been handed to the notification service, else null
 * @param reason a short human reason for any end state other than CONFIRMED, else empty
 * @param replay true when the checkout was already finished before this call, so nothing was done and the stored answer came back
 */
public record CheckoutResult(String checkoutId, State state, String bookingId, String notificationId, String reason, boolean replay) {
}
