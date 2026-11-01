package com.lld.elevator;

/**
 * One stop a car made: doors opened at {@code floor} on tick {@code tick}.
 *
 * <p>{@code kind} says why: a rider inside asked for the floor (CAB), or a hall button was answered (HALL_UP / HALL_DOWN).
 */
public record Served(int carId, int floor, Kind kind, long tick) {

    /** Why the car stopped. */
    public enum Kind {
        /** A rider inside the car pressed this floor. */
        CAB,
        /** The up button on this floor was answered. */
        HALL_UP,
        /** The down button on this floor was answered. */
        HALL_DOWN,
        /** Fire recall: the car reached the lobby and opened its doors once. */
        FIRE_RECALL
    }
}
