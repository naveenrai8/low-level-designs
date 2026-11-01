package com.lld.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The sweep is only a memory saver: running it must never change any answer. */
class SweepDifferentialTest {

    /** Runs the same random operations on two services, one of which sweeps often, and compares every answer as text. */
    @Test
    void sweepNeverChangesAnyAnswer() {
        int totalDropped = 0;
        int totalSmaller = 0;
        for (long seed = 1; seed <= 20; seed++) {
            TestClock c1 = new TestClock();
            TestClock c2 = new TestClock();
            SeatHoldBookingService plain = Support.service(c1, 6);
            SeatHoldBookingService swept = Support.service(c2, 6);
            Random r = new Random(seed);
            List<String> holdIds = new ArrayList<>();
            int dropped = 0;
            for (int step = 0; step < 300; step++) {
                int op = r.nextInt(6);
                int a = 1 + r.nextInt(6);
                int b = 1 + r.nextInt(6);
                String hid = holdIds.isEmpty() ? "h-0" : holdIds.get(r.nextInt(holdIds.size()));
                String key = "k" + r.nextInt(8);
                long secs = r.nextInt(150);
                String x = run(plain, c1, op, a, b, hid, key, secs, holdIds, true);
                String y = run(swept, c2, op, a, b, hid, key, secs, holdIds, false);
                assertEquals(x, y, "seed " + seed + " step " + step);
                if (step % 3 == 0) {
                    dropped += swept.sweep();
                }
                assertEquals(plain.availableSeats("s"), swept.availableSeats("s"));
            }
            totalDropped += dropped;
            totalSmaller += swept.holdRecords() < plain.holdRecords() ? 1 : 0;
        }
        assertTrue(totalDropped > 0, "the sweep must really free things");
        assertTrue(totalSmaller > 0, "the sweep must really shrink the hold records");
    }

    /** Performs one operation and describes the outcome as a string. Only the first (record=true) run adds new hold ids to the shared list. */
    private static String run(SeatHoldBookingService s, TestClock clock, int op, int a, int b, String hid, String key, long secs, List<String> holdIds, boolean record) {
        try {
            switch (op) {
                case 0, 1 -> {
                    Hold h = s.hold("s", a == b ? List.of("A" + a) : List.of("A" + a, "A" + b));
                    if (record) {
                        holdIds.add(h.holdId());
                    }
                    return "hold " + h.holdId() + h.seatIds();
                }
                case 2 -> {
                    ConfirmResult cr = s.confirm(hid, key, "pay" + (a % 2));
                    return "confirm " + cr.booking().bookingId() + cr.replay();
                }
                case 3 -> {
                    s.release(hid);
                    return "released";
                }
                default -> {
                    clock.advance(Duration.ofSeconds(secs));
                    return "tick " + s.seatState("s", "A" + a);
                }
            }
        } catch (BookingException e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }
}
