package com.lld.ticketing;

/**
 * The ticket checkout: hold seats, authorize the money, capture it, confirm the booking, then tell the buyer. One idempotency key covers the whole flow.
 *
 * <p>Error contract (and the HTTP status an adapter would give, following the IETF Idempotency-Key draft):
 * <ul>
 * <li>{@link CheckoutKeyConflictException}: the key was used with a different request. 422.
 * <li>{@link CheckoutInProgressException}: the first call with this key is still running. 409, retry later, nothing changed.
 * <li>{@link IllegalArgumentException}: a blank key (400). A bad request record cannot even be built.
 * <li>Business failures (seats taken, no funds, hold expired) are NOT exceptions. They are end states in the {@link CheckoutResult}, and the same key replays the same answer.
 * <li>Anything else thrown (including a simulated crash) leaves the record where it was. The same key resumes it.
 * </ul>
 */
public interface Checkout {

    /**
     * Runs the checkout, or resumes it from the last recorded step if an earlier call died half way. A finished checkout returns its stored answer with replay true (replay says the purchase was already finished; a waiting confirmation may still be sent by this call).
     */
    CheckoutResult checkout(String idempotencyKey, CheckoutRequest request);

    /**
     * Finds checkouts that are stuck with an expired seat hold and finishes them: it releases the seats and the payment hold, or refunds a capture that can no longer be confirmed. A checkout that fails is skipped and the sweep goes on. Busy checkouts are skipped. Returns how many it finished.
     */
    int sweep();

    /**
     * Hands every waiting confirmation message to the notification service. Safe to call again and again. Busy checkouts are skipped. Returns how many it handed over.
     */
    int flushOutbox();

    /**
     * Forgets finished checkouts older than the key retention (and with no message waiting), so memory is bounded. After that the same key would start a new purchase. Returns how many it forgot.
     */
    int purge();

    /**
     * Returns the recorded state of a checkout, or null for an unknown key. It never waits for a running checkout.
     */
    State stateOf(String idempotencyKey);
}
