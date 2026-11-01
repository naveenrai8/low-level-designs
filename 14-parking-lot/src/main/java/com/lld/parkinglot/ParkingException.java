package com.lld.parkinglot;

/**
 * The error contract of the lot. Every failure is one of these nested types, so callers can catch one type or a precise one.
 *
 * <p>These are programming or business-rule errors, not retryable glitches: a failed call leaves the spots and tickets as they were. The one tidy-up is an expired hold: {@code parkReserved} on a hold that has run out releases that hold's spot and plate, then throws.
 */
public abstract class ParkingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message that names the problem and the id involved. */
    protected ParkingException(String message) {
        super(message);
    }

    /** No free spot of a size that fits the vehicle. */
    public static final class LotFull extends ParkingException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given vehicle type. */
        public LotFull(VehicleType type) {
            super("no free spot for " + type);
        }
    }

    /** The plate is already parked, or already has a live reservation. */
    public static final class DuplicateVehicle extends ParkingException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given plate. */
        public DuplicateVehicle(String plate) {
            super("vehicle already parked or reserved: " + plate);
        }
    }

    /** The ticket id was never issued by this lot. */
    public static final class UnknownTicket extends ParkingException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given ticket id. */
        public UnknownTicket(String id) {
            super("unknown ticket: " + id);
        }
    }

    /** The ticket was valid once, but it has already been used to leave. */
    public static final class TicketAlreadyUsed extends ParkingException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given ticket id. */
        public TicketAlreadyUsed(String id) {
            super("ticket already used: " + id);
        }
    }

    /** The reservation id was never issued, or was already used to park. */
    public static final class UnknownReservation extends ParkingException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given reservation id. */
        public UnknownReservation(String id) {
            super("unknown reservation: " + id);
        }
    }

    /** The hold ran out. This includes losing the spot to another gate at the expiry instant, even if this gate's own clock reading was still inside the hold. */
    public static final class ReservationExpired extends ParkingException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception for the given reservation id. */
        public ReservationExpired(String id) {
            super("reservation expired: " + id);
        }
    }
}
