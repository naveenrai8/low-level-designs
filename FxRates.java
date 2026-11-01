package com.lld.splitwise;

import java.util.Map;

/**
 * Exchange rates, injected so a test (or a real rate service) decides the rate. A rate is a whole number of micros: 1_000_000 means 1 to 1, 920_000 means 1 minor unit of the source is 0.92 minor units of the target.
 *
 * <p>Rates are minor-unit to minor-unit, so this topic assumes currencies with the same number of minor digits. A real system scales by each currency's digits.
 */
public interface FxRates {

    /** Scale of a rate: 1_000_000 micros is a rate of 1.0. */
    long ONE = 1_000_000L;

    /**
     * Returns the rate to turn the {@code from} currency into {@code to}, in micros. Same currency is always {@link #ONE}.
     *
     * @throws IllegalArgumentException if there is no rate for the pair (we never guess)
     */
    long micros(String from, String to);

    /** A fixed table of rates keyed like {@code "EUR>USD"}; it is an immutable copy. */
    static FxRates fixed(Map<String, Long> table) {
        Map<String, Long> copy = Map.copyOf(table);
        return (from, to) -> {
            if (from.equals(to)) {
                return ONE;
            }
            Long rate = copy.get(from + ">" + to);
            if (rate == null || rate <= 0) {
                throw new IllegalArgumentException("no rate for " + from + ">" + to);
            }
            return rate;
        };
    }
}
