package com.lld.elevator;

import java.util.List;

/**
 * The public face of a bank of elevators.
 *
 * <p>Error contract: a floor outside the building, an UP call on the top floor, a DOWN call on floor 0, an IDLE or null direction, or an unknown car id throws {@link IllegalArgumentException}. An illegal mode change (see {@link ModeTable}) throws {@link IllegalStateException}. Refusals that are normal business (fire mode, no car in service) are returned as values, never thrown.
 *
 * <p>All methods are safe to call from many threads.
 */
public interface ElevatorSystem {

    /** Presses a hall button. Returns ACCEPTED with the car that owns it, or a refusal. Pressing the same button twice for the same car is harmless. */
    HallResult hallCall(int floor, Direction direction);

    /** A rider inside car {@code carId} presses a floor. Returns false if the car is in fire mode or maintenance and the press is ignored. */
    boolean cabRequest(int carId, int floor);

    /** Advances the whole building by one tick and returns every stop made in it. */
    List<Served> step();

    /** Turns fire mode on or off for the whole building. Turning on recalls every car and drops all pending requests. Turning it on twice is harmless. */
    void setFire(boolean on);

    /** Takes a car out of dispatch or hands it back. Returns the hall calls that could not be given to another car (empty unless every other car is out of service). */
    List<HallCall> setMaintenance(int carId, boolean on);

    /** Returns a snapshot of every car. */
    List<CarView> cars();

    /** Returns the number of pending stops over all cars. */
    int pending();
}
