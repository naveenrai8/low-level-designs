package com.lld.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Proves the bin layout: exact start, boundaries, order, the rank rule and the stated error bound. */
class LogLinearTest {

    /** Values below 128 each have their own bin and are reported exactly. */
    @Test
    void smallValuesAreExact() {
        for (int v = 0; v < 128; v++) {
            assertEquals(v, LogLinear.indexOf(v));
            assertEquals(v, LogLinear.representative(v));
        }
    }

    /** The first logarithmic bins start where the linear ones end. */
    @Test
    void boundariesBetweenRanges() {
        assertEquals(128, LogLinear.indexOf(128));
        assertEquals(128, LogLinear.indexOf(129));
        assertEquals(129, LogLinear.indexOf(130));
        assertEquals(191, LogLinear.indexOf(255));
        assertEquals(192, LogLinear.indexOf(256));
        assertEquals(LogLinear.SIZE - 1, LogLinear.indexOf(LogLinear.MAX_TRACKABLE));
        assertEquals(LogLinear.SIZE - 1, LogLinear.indexOf(Long.MAX_VALUE));
    }

    /** Every value sits inside the range its bin claims, and bin numbers never go down as values go up. */
    @Test
    void bucketRangesAreConsistentAndMonotone() {
        Random r = new Random(1);
        int previous = -1;
        for (long v = 0; v < 5000; v++) {
            int i = LogLinear.indexOf(v);
            assertTrue(i >= previous);
            previous = i;
            assertTrue(LogLinear.lowerBound(i) <= v && v < LogLinear.lowerBound(i) + LogLinear.width(i));
        }
        for (int n = 0; n < 100_000; n++) {
            long v = (long) (r.nextDouble() * LogLinear.MAX_TRACKABLE);
            int i = LogLinear.indexOf(v);
            assertTrue(LogLinear.lowerBound(i) <= v && v < LogLinear.lowerBound(i) + LogLinear.width(i), "value " + v);
        }
    }

    /** The nearest-rank position is exact when q times total is a whole number, even if floating point gives 7.000000000000001 (0.7 x 10, 0.28 x 25). */
    @Test
    void rankIgnoresFloatingPointNoise() {
        assertEquals(7, LogLinear.rank(0.7, 10));
        assertEquals(7, LogLinear.rank(0.28, 25));
        assertEquals(14, LogLinear.rank(0.56, 25));
        assertEquals(8, LogLinear.rank(0.71, 10));
        assertEquals(1, LogLinear.rank(0.0, 10));
        assertEquals(10, LogLinear.rank(1.0, 10));
    }

    /** Reporting the middle of the bin is within the stated relative error for any value in range. */
    @Test
    void relativeErrorBoundHolds() {
        Random r = new Random(2);
        for (int n = 0; n < 200_000; n++) {
            long v = 1 + (long) (Math.pow(2, r.nextDouble() * 40) - 1);
            long rep = LogLinear.representative(LogLinear.indexOf(v));
            assertTrue(Math.abs(rep - v) <= v * LogLinear.MAX_RELATIVE_ERROR, "value " + v + " reported as " + rep);
        }
    }
}
