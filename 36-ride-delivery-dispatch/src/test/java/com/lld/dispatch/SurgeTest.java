package com.lld.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** F3: surge is monotone, capped, rounded as stated, and locked at quote time. */
class SurgeTest {

    private final RatioSurgePolicy policy = new RatioSurgePolicy(5_000, 25_000);

    /** No surge while drivers cover the requests. */
    @Test
    void noSurgeWhenSupplyCoversDemand() {
        assertEquals(10_000, policy.surgeBps(0, 0));
        assertEquals(10_000, policy.surgeBps(3, 3));
        assertEquals(10_000, policy.surgeBps(2, 9));
    }

    /** More open requests never lowers the multiplier, and more drivers never raises it (checked over a whole grid of counts). */
    @Test
    void monotoneInDemandAndSupply() {
        for (int drivers = 0; drivers <= 30; drivers++) {
            int prev = 0;
            for (int open = 0; open <= 200; open++) {
                int bps = policy.surgeBps(open, drivers);
                assertTrue(bps >= prev, "open " + open + " drivers " + drivers);
                prev = bps;
            }
        }
        for (int open = 0; open <= 100; open++) {
            int prev = Integer.MAX_VALUE;
            for (int drivers = 0; drivers <= 60; drivers++) {
                int bps = policy.surgeBps(open, drivers);
                assertTrue(bps <= prev);
                prev = bps;
            }
        }
    }

    /** However extreme the shortage, the multiplier never exceeds the cap. */
    @Test
    void surgeIsCapped() {
        assertEquals(25_000, policy.surgeBps(1_000_000, 0));
        assertEquals(25_000, policy.surgeBps(Integer.MAX_VALUE, 1));
        for (int open = 0; open < 500; open++) {
            assertTrue(policy.surgeBps(open, 1) <= 25_000);
        }
    }

    /** Worked numbers: 6 requests, 4 drivers, slope 5000: shortfall 2 over 4 drivers = 50 percent of a slope = +2500, so 12,500 (1.25x). */
    @Test
    void formulaWorkedExample() {
        assertEquals(12_500, policy.surgeBps(6, 4));
        assertEquals(15_000, policy.surgeBps(2, 1));
    }

    /** Rounding is half up on exact long arithmetic: 999 at 1.25x is 1248.75 so 1249; 1 at 1.0001x is 1.0001 so 1; 2 at 1.2500x is exactly 2.5 so 3. */
    @Test
    void priceRoundsHalfUp() {
        assertEquals(1249, policy.price(999, 12_500));
        assertEquals(1, policy.price(1, 10_001));
        assertEquals(3, policy.price(2, 12_500));
        assertEquals(1000, policy.price(1000, 10_000));
        assertEquals(0, policy.price(0, 25_000));
        assertEquals(Long.MAX_VALUE / 10_000 * 10_000 / 10_000, policy.price(Long.MAX_VALUE / 10_000, 10_000));
    }

    /** Bad input is refused: negative counts, a multiplier under 1.00x, negative fares and a nonsense policy. */
    @Test
    void badInputIsRefused() {
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> policy.surgeBps(-1, 0)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> policy.price(100, 9_999)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> policy.price(-1, 10_000)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new RatioSurgePolicy(1, 9_999)).code());
        assertThrows(ArithmeticException.class, () -> policy.price(Long.MAX_VALUE, 25_000));
    }

    /** The price is fixed at request time: later requests push surge up, but the first quote does not change. */
    @Test
    void quoteIsLockedAtRequestTime() {
        ManualScheduler s = new ManualScheduler();
        Fleet f = new Fleet(1000, s::nowMs);
        f.register(1, new Point(0, 0), 50, 10);
        f.goOnline(1, new Point(10, 10));
        // a long offer timeout and window so the requests stay open and count as demand
        Dispatcher d = new Dispatcher(f, s, policy, ScoringStrategy.DISTANCE, new GreedyAssigner(), 60_000, 60_000, 1000);
        Quote q1 = d.submit(new Request(1, new Point(0, 0)));
        assertEquals(10_000, q1.surgeBps());
        assertEquals(1000, q1.amountMinor());
        Quote q2 = d.submit(new Request(2, new Point(1, 1)));
        Quote q3 = d.submit(new Request(3, new Point(2, 2)));
        assertEquals(15_000, q2.surgeBps());
        assertEquals(20_000, q3.surgeBps());
        assertEquals(2000, q3.amountMinor());
        assertEquals(q1, d.quote(1));
        assertEquals(q2, d.quote(2));
        s.advance(60_000);
        d.accept(d.offerOf(3));
        assertEquals(q1, d.quote(1));
        assertEquals(q2, d.quote(2));
    }
}
