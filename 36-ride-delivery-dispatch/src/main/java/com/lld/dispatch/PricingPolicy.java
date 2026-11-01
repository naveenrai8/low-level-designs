package com.lld.dispatch;

/**
 * Decides the surge multiplier of a cell and the price. <b>Surge</b> is a price multiplier that rises when riders outnumber drivers in an area, to attract drivers and ration rides. A <b>basis point</b> (bps) is 1/100 of a percent: 10,000 bps is 1.00x.
 * <p>Error contract: counts must be non-negative and base fare non-negative, otherwise OUT_OF_RANGE. Implementations must return a multiplier of at least 10,000 and at most their cap, and be non-decreasing in demand.
 */
public interface PricingPolicy {

    /**
     * Surge for one cell.
     *
     * @param openRequests requests in the cell not yet assigned, including the one being priced
     * @param availableDrivers available drivers in the cell
     * @return the multiplier in basis points
     */
    int surgeBps(int openRequests, int availableDrivers);

    /**
     * Prices a ride. Rounding rule: base * bps / 10,000 rounded half up to a whole minor unit, using exact long arithmetic.
     *
     * @param baseFareMinor the base fare in minor units
     * @param surgeBps the multiplier
     * @return the price in minor units
     */
    default long price(long baseFareMinor, int surgeBps) {
        if (baseFareMinor < 0 || surgeBps < 10_000) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "base " + baseFareMinor + " bps " + surgeBps);
        }
        return Math.addExact(Math.multiplyExact(baseFareMinor, (long) surgeBps), 5_000L) / 10_000L;
    }
}
