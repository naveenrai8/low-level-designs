package com.lld.vending;

/** The four resting situations a vending machine can be in. Which moves are allowed from each one is written down in {@link TransitionTable}. */
public enum State {
    /** Nobody is using the machine. Waiting for someone to pick a product. */
    IDLE,
    /** A product is picked and the machine is collecting money for it. */
    SELECTING,
    /** Enough money is in. The customer can now press dispense or cancel. */
    PAID,
    /** The motor is running. No customer input is accepted until it finishes or jams. */
    DISPENSING
}
