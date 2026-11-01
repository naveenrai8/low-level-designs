package com.lld.elevator;

/** Things that can happen to a car's mode. */
public enum ModeEvent {
    /** A technician takes the car out of service. */
    MAINTENANCE_ON,
    /** The technician hands the car back. */
    MAINTENANCE_OFF,
    /** The fire alarm goes off. */
    FIRE_ON,
    /** The fire alarm is cleared. */
    FIRE_OFF
}
