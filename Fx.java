package com.lld.splitwise;

import java.util.Map;
import java.util.TreeMap;

/**
 * Converts a whole set of balances at one rate without breaking the "everything adds up to 0" rule.
 */
final class Fx {

    /** Not instantiable: only static helpers. */
    private Fx() {
    }

    /**
     * Converts every net to the target currency. Each value is rounded DOWN first, which leaves the total a few units below 0; then +1 goes to the people with the largest rounding remainders (ties by smaller user id) until the total is exactly 0 again.
     *
     * <p>This is the same largest-remainder trick as splitting a bill, so the converted nets still add up to exactly 0 and nobody moves by more than 1 unit from the exact value.
     *
     * @param nets user id to net in the source currency, adding up to 0
     * @param rateMicros the rate in micros, above 0
     * @return user id to net in the target currency, adding up to 0
     * @throws ArithmeticException if a product overflows a long
     */
    static Map<String, Long> convert(Map<String, Long> nets, long rateMicros) {
        Map<String, Long> result = new TreeMap<>();
        Map<String, Long> fraction = new TreeMap<>();
        long sum = 0;
        for (Map.Entry<String, Long> e : new TreeMap<>(nets).entrySet()) {
            long scaled = Math.multiplyExact(e.getValue(), rateMicros);
            long floor = Math.floorDiv(scaled, FxRates.ONE);
            result.put(e.getKey(), floor);
            fraction.put(e.getKey(), Math.floorMod(scaled, FxRates.ONE));
            sum += floor;
        }
        long missing = -sum;
        // The fractions add up to a whole multiple of ONE because the exact total is 0, so missing is a whole number below the user count.
        while (missing > 0) {
            String best = null;
            for (Map.Entry<String, Long> f : fraction.entrySet()) {
                if (best == null || f.getValue() > fraction.get(best)) {
                    best = f.getKey();
                }
            }
            result.merge(best, 1L, Long::sum);
            fraction.put(best, -1L);
            missing--;
        }
        return result;
    }
}
