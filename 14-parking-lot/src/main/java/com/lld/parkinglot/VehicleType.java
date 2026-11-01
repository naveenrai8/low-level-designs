package com.lld.parkinglot;

/** The kinds of vehicle the lot accepts. Each kind needs a spot of at least a certain size. */
public enum VehicleType {
    /** A motorcycle: fits any spot, but we try the small spots first. */
    MOTORCYCLE,
    /** A car: needs a medium or a large spot. */
    CAR,
    /** A truck: needs a large spot. */
    TRUCK
}
