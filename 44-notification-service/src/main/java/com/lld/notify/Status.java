package com.lld.notify;

/** Where a notification is in its life. PENDING can still change; the other three are final. */
public enum Status {
    /** Waiting to be sent: new, deferred by quiet hours or the rate limit, or waiting for a retry. */
    PENDING,
    /** The channel accepted it. */
    SENT,
    /** Gave up: a permanent failure or too many attempts. It is in the dead letter queue (DLQ) with its reason. */
    DEAD,
    /** The user turned this channel off, so nothing was sent. This is a normal outcome, not a failure. */
    SUPPRESSED
}
