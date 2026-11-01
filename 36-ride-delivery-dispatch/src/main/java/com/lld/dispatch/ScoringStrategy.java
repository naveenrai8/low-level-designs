package com.lld.dispatch;

/**
 * Says how bad it would be to give a request to a driver. Lower is better. The batch assigner minimises the sum of these costs.
 * <p>Error contract: the cost must be between 0 and {@link #MAX_COST}. Anything else makes the matcher throw BAD_COST before any state changes.
 */
@FunctionalInterface
public interface ScoringStrategy {

    /** The largest legal cost (2^32); sums of up to 256 of them still fit in a long with room to spare. */
    long MAX_COST = 1L << 32;

    /**
     * Scores one pair.
     *
     * @param pickup where the rider is
     * @param driver the candidate driver
     * @return the cost, lower is better
     */
    long cost(Point pickup, DriverInfo driver);

    /** Plain pickup distance in whole metres (rounded up). */
    ScoringStrategy DISTANCE = (pickup, d) -> Geometry.isqrtCeil(d.where().distSq(pickup));

    /**
     * Distance plus a penalty for a lower rating: every missing tenth of a star below 5.0 adds the given number of metres.
     *
     * @param metresPerTenth the penalty per missing tenth star
     * @return the strategy
     */
    static ScoringStrategy distanceAndRating(long metresPerTenth) {
        if (metresPerTenth < 0) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "penalty " + metresPerTenth);
        }
        return (pickup, d) -> Math.addExact(Geometry.isqrtCeil(d.where().distSq(pickup)), Math.multiplyExact(50L - d.ratingTenths(), metresPerTenth));
    }

    /** A rough pickup time in tenths of a second: distance divided by the driver's speed. */
    ScoringStrategy ETA = (pickup, d) -> Geometry.isqrtCeil(d.where().distSq(pickup)) * 10 / d.speedMps();
}
