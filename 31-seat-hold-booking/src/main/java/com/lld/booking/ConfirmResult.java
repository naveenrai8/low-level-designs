package com.lld.booking;

/**
 * The answer to a successful confirm.
 *
 * @param booking the booking (the same object contents on a replay)
 * @param replay false for the first call with this idempotency key, true when an earlier answer was returned again (HTTP maps this to 201 versus 200)
 */
public record ConfirmResult(Booking booking, boolean replay) {
}
