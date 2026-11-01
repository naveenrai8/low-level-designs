package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves two volatile fields can show a half-updated state and a single atomic reference cannot.
 */
class ParkingSpotTest {

    /**
     * Proves that a reader between the two writes sees the spot as occupied with no vehicle.
     */
    @Test
    void bugReaderBetweenTwoVolatileWritesSeesOccupiedByNobody() {
        List<String> seen = new ArrayList<>();
        ParkingSpot[] spot = new ParkingSpot[1];
        spot[0] = new TwoVolatileSpot(() -> seen.add(spot[0].isOccupied() + "/" + spot[0].occupant()));

        assertTrue(spot[0].park("KA-01"));
        assertEquals(List.of("true/" + Optional.empty()), seen, "occupied, by nobody");
    }

    /**
     * Proves the atomic spot starts free, accepts the first car, rejects the second, and reports the first car as the occupant.
     */
    @Test
    void fixOneCasIsBothCheckAndActAndNeverTorn() {
        ParkingSpot spot = new AtomicSpot();
        assertFalse(spot.isOccupied());
        assertTrue(spot.park("KA-01"));
        assertFalse(spot.park("KA-02"));
        assertEquals(Optional.of("KA-01"), spot.occupant());
    }
}
