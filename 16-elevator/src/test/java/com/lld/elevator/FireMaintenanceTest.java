package com.lld.elevator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the mode table, fire recall and maintenance. */
class FireMaintenanceTest {

    private final VirtualTicker ticker = new VirtualTicker();

    /** Builds a 10-floor, 2-car building with cars at floors 6 and 3. */
    private Building building() {
        return new Building(10, new int[] {6, 3}, new NearestDispatcher(), ticker);
    }

    /** Every legal row of the table gives the expected next mode. */
    @Test
    void legalTransitions() {
        assertEquals(CarMode.MAINTENANCE, ModeTable.next(CarMode.NORMAL, ModeEvent.MAINTENANCE_ON));
        assertEquals(CarMode.FIRE, ModeTable.next(CarMode.NORMAL, ModeEvent.FIRE_ON));
        assertEquals(CarMode.NORMAL, ModeTable.next(CarMode.MAINTENANCE, ModeEvent.MAINTENANCE_OFF));
        assertEquals(CarMode.FIRE, ModeTable.next(CarMode.MAINTENANCE, ModeEvent.FIRE_ON));
        assertEquals(CarMode.NORMAL, ModeTable.next(CarMode.FIRE, ModeEvent.FIRE_OFF));
    }

    /** Every pair not in the table throws, so no hidden transition exists. */
    @Test
    void illegalTransitionsThrow() {
        int legal = 0;
        for (CarMode m : CarMode.values()) {
            for (ModeEvent e : ModeEvent.values()) {
                try {
                    ModeTable.next(m, e);
                    legal++;
                } catch (IllegalStateException expected) {
                    // illegal pair, as intended
                }
            }
        }
        assertEquals(5, legal);
        assertThrows(IllegalStateException.class, () -> ModeTable.next(CarMode.FIRE, ModeEvent.MAINTENANCE_ON));
    }

    /** Fire recalls every car to floor 0 (doors open once per car), drops pending work, and ignores new hall calls and cab presses. */
    @Test
    void fireRecallsAndIgnoresCalls() {
        Building b = building();
        b.hallCall(8, Direction.DOWN);
        b.cabRequest(0, 9);
        b.setFire(true);
        assertEquals(0, b.pending());
        assertEquals(HallResult.Status.REJECTED_FIRE, b.hallCall(2, Direction.UP).status());
        assertFalse(b.cabRequest(0, 5));
        List<Served> stops = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            stops.addAll(b.step());
        }
        assertEquals(2, stops.size());
        assertTrue(stops.stream().allMatch(x -> x.kind() == Served.Kind.FIRE_RECALL && x.floor() == 0));
        assertEquals(List.of(0, 0), b.cars().stream().map(CarView::floor).toList());
        assertEquals(0, b.pending());
    }

    /** Clearing fire returns cars to NORMAL and calls work again; turning fire on twice is harmless. */
    @Test
    void fireOffRestoresService() {
        Building b = building();
        b.setFire(true);
        b.setFire(true);
        b.setFire(false);
        assertEquals(CarMode.NORMAL, b.mode(0));
        assertEquals(HallResult.Status.ACCEPTED, b.hallCall(2, Direction.UP).status());
    }

    /** A car in maintenance is never picked and its queued hall calls move to another car. */
    @Test
    void maintenanceRemovesCarFromDispatch() {
        Building b = building();
        assertEquals(1, b.hallCall(4, Direction.UP).carId());
        List<HallCall> orphans = b.setMaintenance(1, true);
        assertTrue(orphans.isEmpty());
        assertEquals(1, b.cars().get(0).pending());
        assertEquals(0, b.hallCall(3, Direction.UP).carId());
        assertFalse(b.cabRequest(1, 5));
        int floorBefore = b.cars().get(1).floor();
        b.step();
        assertEquals(floorBefore, b.cars().get(1).floor());
        b.setMaintenance(1, false);
        assertEquals(CarMode.NORMAL, b.mode(1));
    }

    /** When every car is out of service the call is refused, not lost silently. */
    @Test
    void noCarAvailable() {
        Building b = building();
        b.setMaintenance(0, true);
        b.setMaintenance(1, true);
        assertEquals(HallResult.Status.NO_CAR_AVAILABLE, b.hallCall(2, Direction.UP).status());
    }

    /** If the last other car is taken out, the released hall calls come back as orphans. */
    @Test
    void orphanedCallsAreReported() {
        Building b = building();
        b.setMaintenance(0, true);
        b.hallCall(4, Direction.UP);
        List<HallCall> orphans = b.setMaintenance(1, true);
        assertEquals(List.of(new HallCall(4, Direction.UP)), orphans);
    }

    /** Maintenance cannot be switched on during fire (the table forbids it). */
    @Test
    void maintenanceDuringFireRejected() {
        Building b = building();
        b.setFire(true);
        assertThrows(IllegalStateException.class, () -> b.setMaintenance(0, true));
    }

    /** Bad input is rejected with IllegalArgumentException. */
    @Test
    void inputValidation() {
        Building b = building();
        assertThrows(IllegalArgumentException.class, () -> b.hallCall(9, Direction.UP));
        assertThrows(IllegalArgumentException.class, () -> b.hallCall(0, Direction.DOWN));
        assertThrows(IllegalArgumentException.class, () -> b.hallCall(3, Direction.IDLE));
        assertThrows(IllegalArgumentException.class, () -> b.hallCall(10, Direction.DOWN));
        assertThrows(IllegalArgumentException.class, () -> b.cabRequest(5, 1));
    }

    /** A car under repair stays under repair after a fire is cleared, and the technician can still hand it back. */
    @Test
    void maintenanceSurvivesFire() {
        Building b = building();
        b.setMaintenance(1, true);
        b.setFire(true);
        assertEquals(CarMode.FIRE, b.mode(1));
        b.setFire(false);
        assertEquals(CarMode.NORMAL, b.mode(0));
        assertEquals(CarMode.MAINTENANCE, b.mode(1));
        assertEquals(0, b.hallCall(2, Direction.UP).carId());
        b.setMaintenance(1, false);
        assertEquals(CarMode.NORMAL, b.mode(1));
    }
}
