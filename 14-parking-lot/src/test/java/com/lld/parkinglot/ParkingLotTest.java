package com.lld.parkinglot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Single-threaded behaviour: placement, fees, double-park, bad tickets and reservations. */
class ParkingLotTest {

    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");
    private MutableClock clock;
    private InMemoryParkingLot lot;

    /** Builds a two-level lot (level 0: 1 small, 1 medium; level 1: 1 large) with $2/hour for cars. */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        PricingStrategy pricing = new HourlyPricing(Map.of(VehicleType.MOTORCYCLE, 100L, VehicleType.CAR, 200L, VehicleType.TRUCK, 500L));
        lot = new InMemoryParkingLot(List.of(Map.of(SpotType.SMALL, 1, SpotType.MEDIUM, 1), Map.of(SpotType.LARGE, 1)), pricing, clock);
    }

    /** Makes a car with the given plate. */
    private static Vehicle car(String plate) {
        return new Vehicle(plate, VehicleType.CAR);
    }

    /** Proves a car takes the medium spot first and then the large one, and a small spot is never given to a car. */
    @Test
    void carsUseSmallestFittingSpotAndNeverSmall() {
        assertEquals("L0-MEDIUM-0", lot.park(car("A")).spotId());
        assertEquals("L1-LARGE-0", lot.park(car("B")).spotId());
        assertThrows(ParkingException.LotFull.class, () -> lot.park(car("C")));
        assertEquals(1, lot.freeSpots(SpotType.SMALL));
    }

    /** Proves a truck only fits the large spot. */
    @Test
    void truckNeedsLarge() {
        lot.park(new Vehicle("T1", VehicleType.TRUCK));
        assertThrows(ParkingException.LotFull.class, () -> lot.park(new Vehicle("T2", VehicleType.TRUCK)));
    }

    /** Proves the fee boundaries: 0 minutes is free, 1 minute and exactly 60 minutes cost 1 hour, 60 minutes plus 1 second costs 2 hours. */
    @Test
    void feeRoundsPartialHoursUpAtTheBoundaries() {
        assertEquals(0L, feeAfter(Duration.ZERO));
        assertEquals(200L, feeAfter(Duration.ofMinutes(1)));
        assertEquals(200L, feeAfter(Duration.ofMinutes(60)));
        assertEquals(400L, feeAfter(Duration.ofMinutes(60).plusSeconds(1)));
        assertEquals(400L, feeAfter(Duration.ofMinutes(60).plusNanos(1)));
        assertEquals(200L, feeAfter(Duration.ofMinutes(60).minusNanos(1)));
        assertEquals(400L, feeAfter(Duration.ofMinutes(120)));
    }

    /** Parks a car in a fresh one-spot lot, waits the stay on the fake clock, and returns the fee; then rewinds the clock. */
    private long feeAfter(Duration stay) {
        InMemoryParkingLot l = new InMemoryParkingLot(List.of(Map.of(SpotType.MEDIUM, 1)), lot0Pricing(), clock);
        Ticket t = l.park(car("X"));
        clock.advance(stay);
        long fee = l.unpark(t.id()).fee();
        clock.advance(stay.negated());
        return fee;
    }

    /** Hourly pricing used by the tests: 100 for a motorcycle, 200 for a car, 500 for a truck. */
    private static PricingStrategy lot0Pricing() {
        return new HourlyPricing(Map.of(VehicleType.MOTORCYCLE, 100L, VehicleType.CAR, 200L, VehicleType.TRUCK, 500L));
    }

    /** Proves a vehicle that is already parked cannot park again, and can after it leaves. */
    @Test
    void parkedVehicleCannotParkTwice() {
        Ticket t = lot.park(car("A"));
        assertThrows(ParkingException.DuplicateVehicle.class, () -> lot.park(car("A")));
        lot.unpark(t.id());
        lot.park(car("A"));
    }

    /** Proves an unknown ticket and a used ticket fail with different, clear errors, and the used one does not free a spot twice. */
    @Test
    void unknownAndUsedTicketsFail() {
        assertThrows(ParkingException.UnknownTicket.class, () -> lot.unpark("nope"));
        Ticket t = lot.park(car("A"));
        lot.unpark(t.id());
        assertThrows(ParkingException.TicketAlreadyUsed.class, () -> lot.unpark(t.id()));
        Ticket b = lot.park(car("B"));
        lot.park(car("C"));
        assertEquals(0, lot.freeSpots(SpotType.MEDIUM) + lot.freeSpots(SpotType.LARGE));
        assertThrows(ParkingException.TicketAlreadyUsed.class, () -> lot.unpark(t.id()));
        // A double unpark must not free the spot B now holds.
        assertEquals(0, lot.freeSpots(SpotType.MEDIUM) + lot.freeSpots(SpotType.LARGE));
        assertEquals("L0-MEDIUM-0", b.spotId());
    }

    /** Proves a clock that went backwards is refused and the ticket still works afterwards. */
    @Test
    void backwardsClockKeepsTicketUsable() {
        Ticket t = lot.park(car("A"));
        clock.advance(Duration.ofMinutes(-5));
        assertThrows(IllegalArgumentException.class, () -> lot.unpark(t.id()));
        clock.advance(Duration.ofMinutes(65));
        assertEquals(200L, lot.unpark(t.id()).fee());
    }

    /** Proves the hold is half-open: valid at the last instant before the TTL ends, expired at exactly start plus TTL. */
    @Test
    void holdIsValidUntilExactlyExpiryExclusive() {
        Reservation r = lot.reserve(car("A"), Duration.ofMinutes(10));
        assertEquals(T0.plus(Duration.ofMinutes(10)), r.expiresAt());
        clock.advance(Duration.ofMinutes(10).minusNanos(1));
        Ticket t = lot.parkReserved(r.id());
        assertEquals(r.spotId(), t.spotId());
    }

    /** Proves a hold is expired at exactly the boundary instant. */
    @Test
    void holdIsExpiredAtTheBoundaryInstant() {
        Reservation r = lot.reserve(car("A"), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(10));
        assertThrows(ParkingException.ReservationExpired.class, () -> lot.parkReserved(r.id()));
        assertEquals(1, lot.freeSpots(SpotType.MEDIUM));
    }

    /** Proves a held spot is not free, but frees itself at the expiry instant and can then be given to someone else. */
    @Test
    void heldSpotIsBlockedThenReusableAfterExpiry() {
        lot.park(car("Z"));
        Reservation r = lot.reserve(car("A"), Duration.ofMinutes(10));
        assertEquals(0, lot.freeSpots(SpotType.LARGE));
        assertThrows(ParkingException.LotFull.class, () -> lot.park(car("B")));
        clock.advance(Duration.ofMinutes(10));
        assertEquals(1, lot.freeSpots(SpotType.LARGE));
        Ticket b = lot.park(car("B"));
        assertEquals(r.spotId(), b.spotId());
        assertThrows(ParkingException.UnknownReservation.class, () -> lot.parkReserved(r.id()));
    }

    /** Proves a reservation can be used once only, and a reserved plate cannot park or reserve again. */
    @Test
    void reservationUsedOnceAndBlocksSamePlate() {
        Reservation r = lot.reserve(car("A"), Duration.ofMinutes(10));
        assertThrows(ParkingException.DuplicateVehicle.class, () -> lot.park(car("A")));
        lot.parkReserved(r.id());
        assertThrows(ParkingException.UnknownReservation.class, () -> lot.parkReserved(r.id()));
        assertThrows(ParkingException.DuplicateVehicle.class, () -> lot.reserve(car("A"), Duration.ofMinutes(1)));
    }

    /** Proves a zero or negative TTL is rejected. */
    @Test
    void nonPositiveTtlRejected() {
        assertThrows(IllegalArgumentException.class, () -> lot.reserve(car("A"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> lot.reserve(car("A"), Duration.ofSeconds(-1)));
    }

    /** Proves a failed park (lot full) does not leave the plate locked. */
    @Test
    void failedParkReleasesPlate() {
        lot.park(car("A"));
        lot.park(car("B"));
        assertThrows(ParkingException.LotFull.class, () -> lot.park(car("C")));
        lot.unpark("T1");
        assertNotEquals(null, lot.park(car("C")));
    }

    /** Proves the three pricing strategies: flat ignores length, weekend uses the entry day in the given zone. */
    @Test
    void flatAndWeekendPricing() {
        Map<VehicleType, Long> flat = Map.of(VehicleType.MOTORCYCLE, 50L, VehicleType.CAR, 300L, VehicleType.TRUCK, 900L);
        FlatPricing f = new FlatPricing(flat);
        assertEquals(300L, f.fee(VehicleType.CAR, T0, T0.plus(Duration.ofHours(30))));
        WeekendPricing w = new WeekendPricing(lot0Pricing(), f, java.time.ZoneOffset.UTC);
        Instant friday = Instant.parse("2026-01-09T10:00:00Z");
        Instant saturday = Instant.parse("2026-01-10T10:00:00Z");
        assertEquals(400L, w.fee(VehicleType.CAR, friday, friday.plus(Duration.ofHours(2))));
        assertEquals(300L, w.fee(VehicleType.CAR, saturday, saturday.plus(Duration.ofHours(2))));
        assertThrows(IllegalArgumentException.class, () -> f.fee(VehicleType.CAR, saturday, friday));
    }

    /** Proves the weekend rule uses the day in the given zone: Friday 23:00 UTC is already Saturday in Tokyo. */
    @Test
    void weekendPricingUsesTheConfiguredZone() {
        FlatPricing flat = new FlatPricing(Map.of(VehicleType.MOTORCYCLE, 50L, VehicleType.CAR, 300L, VehicleType.TRUCK, 900L));
        Instant fridayNightUtc = Instant.parse("2026-01-09T23:00:00Z");
        Instant exit = fridayNightUtc.plus(Duration.ofHours(1));
        assertEquals(200L, new WeekendPricing(lot0Pricing(), flat, java.time.ZoneOffset.UTC).fee(VehicleType.CAR, fridayNightUtc, exit));
        assertEquals(300L, new WeekendPricing(lot0Pricing(), flat, java.time.ZoneId.of("Asia/Tokyo")).fee(VehicleType.CAR, fridayNightUtc, exit));
    }

    /** Proves the WeekendPricing constructor rejects missing arguments. */
    @Test
    void weekendPricingRejectsNulls() {
        PricingStrategy p = lot0Pricing();
        assertThrows(NullPointerException.class, () -> new WeekendPricing(null, p, java.time.ZoneOffset.UTC));
        assertThrows(NullPointerException.class, () -> new WeekendPricing(p, null, java.time.ZoneOffset.UTC));
        assertThrows(NullPointerException.class, () -> new WeekendPricing(p, p, null));
    }
}
