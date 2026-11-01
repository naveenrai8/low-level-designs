package com.lld.vending;

/** Thrown by a {@link Dispenser} when the product did not come out (stuck spiral, no cup, heater fault). The machine turns it into a full refund. */
public final class DispenseJamException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Carries a short reason for logs and telemetry. */
    public DispenseJamException(String message) {
        super(message);
    }
}
