package com.lld.mistakes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code double} split loses or creates cents, that a 0.01 tolerance test only sometimes notices, and that the long-based largest-remainder split is exact.
 */
class MoneySplitTest {

    /**
     * Proves each double split is off by exactly one cent, and that the old 0.01-tolerance assertion passes for $10 and $20 but fails for $100. Whether it notices is luck.
     * <p>JUnit's delta check is {@code |expected - actual| <= delta}, and in binary floating point one cent comes out just under or just over 0.01.
     */
    @Test
    void bugDoubleSplitLeaksACentAndWhetherADelta001TestNoticesIsLuck() {
        // Each split is off by exactly one cent. JUnit's delta check is |expected - actual| <= delta, and in binary floating point that one cent comes out just under or just over 0.01.
        assertEquals(9_99L, cents(DoubleSplit.split(10.00, 3)), "$10.00 / 3 -> $9.99: a cent lost");
        assertEquals(20_01L, cents(DoubleSplit.split(20.00, 3)), "$20.00 / 3 -> $20.01: a cent created");
        assertEquals(99_99L, cents(DoubleSplit.split(100.00, 3)), "$100.00 / 3 -> $99.99: a cent lost");

        assertEquals(10.00, sum(DoubleSplit.split(10.00, 3)), 0.01, "the old reviews' assertion PASSES here");
        assertEquals(20.00, sum(DoubleSplit.split(20.00, 3)), 0.01, "and here");
        assertTrue(Math.abs(100.00 - sum(DoubleSplit.split(100.00, 3))) > 0.01, "but would fail here: luck, not a test");
    }

    /**
     * Adds up the shares as doubles.
     */
    private static double sum(double[] shares) {
        return Arrays.stream(shares).sum();
    }

    /**
     * Converts the summed shares to whole cents.
     */
    private static long cents(double[] shares) {
        return Math.round(sum(shares) * 100);
    }

    /**
     * Proves that 0.1 + 0.2 is not 0.3 in {@code double}.
     */
    @Test
    void bugDoubleArithmeticIsNotDecimal() {
        assertNotEquals(0.3, 0.1 + 0.2);
    }

    /**
     * Proves equal splits hand out every unit: 10,000 over 3 gives 3334, 3333, 3333, and edge cases like 2 over 3 and 0 over 3 also add up.
     */
    @Test
    void fixEqualSplitConservesEveryMinorUnit() {
        assertArrayEquals(new long[] {3334, 3333, 3333}, MoneySplit.equally(10_000, 3));
        assertArrayEquals(new long[] {1, 1, 0}, MoneySplit.equally(2, 3));
        assertArrayEquals(new long[] {0, 0, 0}, MoneySplit.equally(0, 3));
    }

    /**
     * Proves a weighted split gives leftover units to the largest remainders, with ties going to the lower index.
     * <p>Example: 1000 by weights 1:1:1:3 is exactly 166.67, 166.67, 166.67, 500. The floors add to 998, so 2 units are left, and they go to the first two shares.
     */
    @Test
    void fixWeightedSplitUsesLargestRemainder() {
        // 1000 by 1:1:1:3 -> exact 166.67, 166.67, 166.67, 500. Floors 166+166+166+500 = 998, 2 left over, which go to the two largest remainders (ties to the lower index).
        assertArrayEquals(new long[] {167, 167, 166, 500}, MoneySplit.byWeights(1_000, new long[] {1, 1, 1, 3}));
    }

    /**
     * Proves over 10,000 random totals and weight sets that the shares add up exactly and each share is within one unit of its exact proportion.
     */
    @Test
    void fixPropertySumIsExactAndEachShareIsWithinOneUnitOfExact() {
        Random random = new Random(42);
        for (int trial = 0; trial < 10_000; trial++) {
            long total = random.nextLong(10_000_000);
            long[] weights = random.longs(1 + random.nextInt(8), 0, 100).toArray();
            if (Arrays.stream(weights).sum() == 0) {
                weights[0] = 1;
            }
            long[] shares = MoneySplit.byWeights(total, weights);
            assertEquals(total, Arrays.stream(shares).sum());
            long weightSum = Arrays.stream(weights).sum();
            for (int i = 0; i < shares.length; i++) {
                double exact = (double) total * weights[i] / weightSum;
                assertTrue(Math.abs(shares[i] - exact) < 1.0, () -> "share off by >= 1 unit");
            }
        }
    }

    /**
     * Proves zero parts, all-zero weights and a negative total are rejected, and an overflow throws instead of wrapping.
     */
    @Test
    void fixRejectsInputsThatCannotBeSplit() {
        assertThrows(IllegalArgumentException.class, () -> MoneySplit.equally(100, 0));
        assertThrows(IllegalArgumentException.class, () -> MoneySplit.byWeights(100, new long[] {0, 0}));
        assertThrows(IllegalArgumentException.class, () -> MoneySplit.byWeights(-1, new long[] {1}));
        assertThrows(ArithmeticException.class, () -> MoneySplit.byWeights(Long.MAX_VALUE, new long[] {2, 1}));
    }
}
