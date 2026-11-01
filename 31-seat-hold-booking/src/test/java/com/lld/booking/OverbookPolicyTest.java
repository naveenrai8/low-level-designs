package com.lld.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F3: selling more units than the capacity, through a policy seam. */
class OverbookPolicyTest {

    /** Proves a fixed allowance of 2 on a 3 seat flight lets exactly 5 single-unit holds succeed and the 6th fail. */
    @Test
    void fixedAllowanceSellsCapacityPlusK() {
        SeatHoldBookingService svc = new SeatHoldBookingService(new TestClock(), Support.CONFIG, Support.ids("h-"), Support.ids("b-"), OverbookPolicy.fixedAllowance(2));
        svc.addShow("f", Support.seats(3));
        List<String> open = new ArrayList<>(svc.availableSeats("f"));
        assertEquals(5, open.size());
        for (String seat : open) {
            svc.hold("f", List.of(seat));
        }
        assertThrows(SeatUnavailableException.class, () -> svc.hold("f", List.of("A1")));
    }

    /** Proves the percent policy rounds down and the default policy adds nothing. */
    @Test
    void percentRoundsDownAndNoneAddsNothing() {
        assertEquals(157, OverbookPolicy.percent(5).sellable(150));
        assertEquals(150, OverbookPolicy.NONE.sellable(150));
        assertThrows(IllegalArgumentException.class, () -> OverbookPolicy.fixedAllowance(-1));
    }

    /** Proves a policy that sells fewer units than the capacity is rejected when the show is added. */
    @Test
    void policyBelowCapacityIsRejected() {
        SeatHoldBookingService svc = new SeatHoldBookingService(new TestClock(), Support.CONFIG, Support.ids("h-"), Support.ids("b-"), c -> c - 1);
        assertThrows(IllegalArgumentException.class, () -> svc.addShow("f", Support.seats(3)));
    }

    /** Proves a real seat whose name looks like a generated overbook unit is rejected, so it cannot be silently overwritten. */
    @Test
    void realSeatNamedLikeAnOverbookUnitIsRejected() {
        SeatHoldBookingService svc = new SeatHoldBookingService(new TestClock(), Support.CONFIG, Support.ids("h-"), Support.ids("b-"), OverbookPolicy.fixedAllowance(1));
        assertThrows(IllegalArgumentException.class, () -> svc.addShow("f", java.util.Set.of("A1", "OVERBOOK-1")));
    }
}
