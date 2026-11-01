package com.lld.elevator;

/** Which way a car is travelling, or which way a hall button points. {@code IDLE} only makes sense for a car that has nothing to do. */
public enum Direction {
    /** Towards higher floor numbers. */
    UP,
    /** Towards lower floor numbers. */
    DOWN,
    /** Standing still with no work. */
    IDLE
}
