package com.lld.dispatch;

import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * Remembers where each driver is and answers "who is nearest to this point". A <b>grid index</b> does it by cutting the plane into equal square <b>cells</b> and keeping the drivers of each cell in a list.
 * <p>Error contract: a point outside plus or minus one billion cannot be built (OUT_OF_RANGE). A null argument throws NullPointerException. A call that throws changes nothing. The index is not thread safe; callers lock around it.
 */
public interface DriverIndex {

    /**
     * Adds a driver, or moves an existing one (a location update). A driver is always in exactly one cell.
     *
     * @param driverId the driver
     * @param where the new position
     */
    void upsert(long driverId, Point where);

    /**
     * Forgets a driver.
     *
     * @param driverId the driver
     * @return true if the driver was known
     */
    boolean remove(long driverId);

    /**
     * Finds the closest driver that passes the filter. Equal distances are won by the smaller driver id, so the answer never depends on insertion order.
     *
     * @param from the point to search around
     * @param eligible a filter, for example "is available and has not declined"
     * @return the nearest eligible driver, or empty if none is eligible
     */
    Optional<Match> nearest(Point from, LongPredicate eligible);

    /**
     * Counts the eligible drivers that share a cell with a point. Used for the supply side of surge pricing.
     *
     * @param p the point
     * @param eligible a filter
     * @return how many eligible drivers are in that cell
     */
    int countInCellOf(Point p, LongPredicate eligible);

    /**
     * Names the cell a point falls in.
     *
     * @param p the point
     * @return its cell
     */
    Cell cellOf(Point p);

    /** Returns how many drivers are indexed. */
    int size();
}
