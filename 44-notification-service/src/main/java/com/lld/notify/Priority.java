package com.lld.notify;

/** How important a message is. It only matters for quiet hours. */
public enum Priority {
    /** Normal: held until quiet hours end. */
    NORMAL,
    /** Urgent or critical (a security alert, a fraud warning): sent even during quiet hours. It still respects an opt-out and the rate limit. */
    URGENT
}
