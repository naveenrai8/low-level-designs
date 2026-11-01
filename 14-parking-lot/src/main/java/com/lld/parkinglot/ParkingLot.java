package com.lld.parkinglot;

import java.time.Duration;

/**
 * A multi-level parking lot. All methods are safe to call from many gates at once.
 *
 * <p>Error contract (all {@link ParkingException} subtypes, a failed call leaves spots and tickets unchanged, except that {@code parkReserved} on an expired hold releases that hold's spot and plate before throwing):
 * <ul>
 * <li>{@code park}: {@code LotFull}, {@code DuplicateVehicle}</li>
 * <li>{@code unpark}: {@code UnknownTicket}, {@code TicketAlreadyUsed}</li>
 * <li>{@code reserve}: {@code LotFull}, {@code DuplicateVehicle}</li>
 * <li>{@code parkReserved}: {@code UnknownReservation}, {@code ReservationExpired}</li>
 * </ul>
 */
public interface ParkingLot {

    /** Parks the vehicle in the smallest free spot that fits and returns its ticket. A plate that is already parked or reserved is rejected. */
    Ticket park(Vehicle vehicle);

    /** Takes the ticket back, frees the spot and returns the receipt with the fee. A ticket works once only. */
    Receipt unpark(String ticketId);

    /** Holds a spot for the vehicle for {@code ttl}. The hold is valid until the instant {@code start + ttl}, exclusive. The ttl must be positive. */
    Reservation reserve(Vehicle vehicle, Duration ttl);

    /** Parks a vehicle in the spot it reserved, if the hold is still valid, and returns the ticket. */
    Ticket parkReserved(String reservationId);

    /** Counts the spots of this size that are free right now. A spot whose hold has expired counts as free. */
    int freeSpots(SpotType type);
}
