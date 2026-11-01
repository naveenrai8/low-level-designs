package com.lld.dispatch;

/**
 * A place on a flat plane, in whole metres.
 * <p>Both coordinates must be within plus or minus {@value #LIMIT}. That bound is what makes a squared distance fit in a long: the biggest possible difference is 2e9, its square is 4e18, and two squares add to 8e18, below the long maximum of about 9.22e18.
 * <p>Limit of the model: this is a flat plane, not a sphere. For a city (tens of km) a flat approximation of lat/lon in metres is fine; near the poles, across the date line or over hundreds of km it is wrong and you need a spherical index such as H3 or S2.
 *
 * @param x metres east of the origin (negative means west)
 * @param y metres north of the origin (negative means south)
 */
public record Point(long x, long y) {

    /** The largest allowed absolute coordinate. */
    public static final long LIMIT = 1_000_000_000L;

    /** Rejects a coordinate outside the allowed range, so no later distance can overflow. */
    public Point {
        if (x < -LIMIT || x > LIMIT || y < -LIMIT || y > LIMIT) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "coordinate beyond +-" + LIMIT + ": " + x + "," + y);
        }
    }

    /**
     * Squared straight-line distance to another point, as an exact long. Use it for every comparison: it needs no square root and no double.
     *
     * @param o the other point
     * @return dx*dx + dy*dy
     */
    public long distSq(Point o) {
        long dx = Math.subtractExact(x, o.x);
        long dy = Math.subtractExact(y, o.y);
        return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dy, dy));
    }
}
