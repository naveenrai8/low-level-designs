package com.lld.dispatch;

/** When the ring search may stop. Only CORRECT is for production; the other exists so a test can show the bug. */
public enum StopRule {
    /** Stop when the best distance found is strictly smaller than the closest any farther ring could possibly be. */
    CORRECT,
    /** WRONG: stop after the first ring that contains any eligible driver. A driver one metre away across a cell edge is missed. */
    FIRST_NON_EMPTY_RING
}
