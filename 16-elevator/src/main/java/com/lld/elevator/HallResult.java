package com.lld.elevator;

/** The answer to a hall call: whether it was taken and by which car ({@code carId} is -1 when it was not taken). */
public record HallResult(Status status, int carId) {

    /** What happened to the call. */
    public enum Status {
        /** A car now owns the call. */
        ACCEPTED,
        /** Fire mode is on, so new hall calls are ignored. */
        REJECTED_FIRE,
        /** Every car is out of service. */
        NO_CAR_AVAILABLE
    }
}
