package com.lld.booking;

/** Which order a multi-seat call takes its seat locks in. SORTED is the fix. REQUEST_ORDER exists only so a test can show the deadlock it causes. */
enum LockOrder {
    /** Sorted by seat id, the same global order for everyone (Topic 08). */
    SORTED,
    /** The order the caller listed the seats in. Two callers listing the same seats in opposite orders can deadlock. */
    REQUEST_ORDER
}
