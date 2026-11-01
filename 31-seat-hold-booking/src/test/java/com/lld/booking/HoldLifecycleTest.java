package com.lld.booking;

import static com.lld.booking.Support.l;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** v1: the hold, release, expiry and confirm life cycle, including the exact expiry tick. */
class HoldLifecycleTest {

    private final TestClock clock = new TestClock();
    private final SeatHoldBookingService svc = Support.service(clock, 5);

    /** Proves the README timeline: hold A1 and A2, a rival is refused at +2 minutes, the hold expires exactly at +5, the confirm gets HoldExpired, and the rival then succeeds. */
    @Test
    void readmeTimeline() {
        Hold h = svc.hold("s", l("A1", "A2"));
        assertEquals(TestClock.T0.plus(Duration.ofMinutes(5)), h.expiresAt());
        clock.advance(Duration.ofMinutes(2));
        SeatUnavailableException e = assertThrows(SeatUnavailableException.class, () -> svc.hold("s", l("A2", "A3")));
        assertEquals(List.of("A2"), e.unavailableSeats());
        assertEquals(SeatState.AVAILABLE, svc.seatState("s", "A3"));
        clock.advance(Duration.ofMinutes(3));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k1", "pay-1"));
        assertEquals(1, svc.refundsNeeded().size());
        assertEquals(h.seatIds(), svc.hold("s", l("A2", "A1")).seatIds());
    }

    /** Proves the half-open boundary on both sides: one nanosecond before expiry the seat is HELD, at expiry it is AVAILABLE. */
    @Test
    void expiryIsHalfOpenAtTheExactTick() {
        Hold h = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertEquals(SeatState.HELD, svc.seatState("s", "A1"));
        assertThrows(SeatUnavailableException.class, () -> svc.hold("s", l("A1")));
        clock.advance(Duration.ofNanos(1));
        assertEquals(h.expiresAt(), clock.instant());
        assertEquals(SeatState.AVAILABLE, svc.seatState("s", "A1"));
        svc.hold("s", l("A1"));
    }

    /** Proves a confirm one nanosecond before expiry works and a confirm exactly at expiry fails. */
    @Test
    void confirmBoundary() {
        Hold early = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertEquals("b-1", svc.confirm(early.holdId(), "k1", "p").booking().bookingId());
        Hold late = svc.hold("s", l("A2"));
        clock.advance(Duration.ofMinutes(5));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(late.holdId(), "k2", "p"));
    }

    /** Proves release frees the seats at once and that releasing twice is harmless. */
    @Test
    void releaseFreesSeatsAndIsRepeatable() {
        Hold h = svc.hold("s", l("A1", "A2"));
        svc.release(h.holdId());
        svc.release(h.holdId());
        assertEquals(SeatState.AVAILABLE, svc.seatState("s", "A1"));
        assertThrows(HoldNotFoundException.class, () -> svc.confirm(h.holdId(), "k", "p"));
    }

    /** Proves a booked seat stays booked forever and its hold cannot be released or confirmed again under a new key. */
    @Test
    void bookedIsFinal() {
        Hold h = svc.hold("s", l("A1"));
        svc.confirm(h.holdId(), "k1", "p");
        clock.advance(Duration.ofMinutes(10));
        assertEquals(SeatState.BOOKED, svc.seatState("s", "A1"));
        assertThrows(AlreadyConfirmedException.class, () -> svc.confirm(h.holdId(), "k2", "p"));
        assertThrows(AlreadyConfirmedException.class, () -> svc.release(h.holdId()));
    }

    /** Proves releasing a stale hold id does not free seats that another hold has since taken. */
    @Test
    void releaseOfExpiredHoldDoesNotTouchNewOwner() {
        Hold old = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5));
        Hold fresh = svc.hold("s", l("A1"));
        svc.release(old.holdId());
        assertEquals(SeatState.HELD, svc.seatState("s", "A1"));
        svc.confirm(fresh.holdId(), "k", "p");
    }

    /** Proves bad input is told apart from races: unknown show, unknown seat, empty and duplicate lists. */
    @Test
    void errorContractForBadInput() {
        assertThrows(ShowNotFoundException.class, () -> svc.hold("nope", l("A1")));
        UnknownSeatException u = assertThrows(UnknownSeatException.class, () -> svc.hold("s", l("A1", "Z9")));
        assertEquals(List.of("Z9"), u.unknownSeats());
        assertThrows(IllegalArgumentException.class, () -> svc.hold("s", l()));
        assertThrows(IllegalArgumentException.class, () -> svc.hold("s", l("A1", "A1")));
        assertThrows(HoldNotFoundException.class, () -> svc.release("h-404"));
        assertThrows(IllegalArgumentException.class, () -> svc.addShow("s", Support.seats(1)));
        assertEquals(SeatState.AVAILABLE, svc.seatState("s", "A1"));
    }

    /** Proves a hold id is forgotten (404) only after expiry plus the retention time, and stays "expired" (410) before that. */
    @Test
    void expiredHoldIsRememberedForTheRetentionPeriod() {
        Hold h = svc.hold("s", l("A1"));
        clock.advance(Duration.ofMinutes(5).plus(Duration.ofMinutes(30)).minusNanos(1));
        assertThrows(HoldExpiredException.class, () -> svc.confirm(h.holdId(), "k", "p"));
        clock.advance(Duration.ofNanos(1));
        assertThrows(HoldNotFoundException.class, () -> svc.confirm(h.holdId(), "k2", "p"));
    }

    /** Proves the list of available seats follows holds, bookings and expiry. */
    @Test
    void availableSeatsFollowState() {
        Hold h = svc.hold("s", l("A2"));
        svc.hold("s", l("A4"));
        svc.confirm(h.holdId(), "k", "p");
        assertEquals(l("A1", "A3", "A5"), svc.availableSeats("s"));
        clock.advance(Duration.ofMinutes(5));
        assertTrue(svc.availableSeats("s").contains("A4"));
    }
}
