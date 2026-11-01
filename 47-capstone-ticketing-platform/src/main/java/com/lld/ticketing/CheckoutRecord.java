package com.lld.ticketing;

import com.lld.booking.Hold;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/** What the checkout remembers about one purchase. In production this is one database row; here it is an object, and a "crash" is an exception that leaves it half filled. Every field except {@code state} is read and written only while holding {@code lock}; {@code state} is volatile so {@code stateOf} can read it without waiting. */
final class CheckoutRecord {

    final String key;
    final CheckoutRequest request;
    final ReentrantLock lock = new ReentrantLock();
    volatile State state = State.NEW;
    Hold hold;
    String paymentHoldId;
    String captureEntryId;
    String bookingId;
    String seatsText = "";
    String notificationId;
    boolean notifyPending;
    String reason = "";
    Instant finishedAt;

    /** Creates the record in state NEW for a key and the request it was first used with. */
    CheckoutRecord(String key, CheckoutRequest request) {
        this.key = key;
        this.request = request;
    }
}
