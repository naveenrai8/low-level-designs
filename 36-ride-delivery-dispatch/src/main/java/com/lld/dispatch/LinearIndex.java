package com.lld.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;

/** The brute-force reference: scans every driver. Slow but obviously right, so tests compare the grid against it. */
public final class LinearIndex implements DriverIndex {

    private final Map<Long, Point> where = new LinkedHashMap<>();
    private final long cellSize;

    /**
     * Builds an empty index.
     *
     * @param cellSize only used to answer cellOf and countInCellOf the same way the grid does
     */
    public LinearIndex(long cellSize) {
        if (cellSize < 1 || cellSize > 1_000_000L) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "cell size must be 1..1000000: " + cellSize);
        }
        this.cellSize = cellSize;
    }

    /** Stores or overwrites the driver position. */
    @Override
    public void upsert(long driverId, Point p) {
        where.put(driverId, java.util.Objects.requireNonNull(p));
    }

    /** Forgets the driver. */
    @Override
    public boolean remove(long driverId) {
        return where.remove(driverId) != null;
    }

    /** Looks at every driver and keeps the smallest (distance, id) pair. */
    @Override
    public Optional<Match> nearest(Point from, LongPredicate eligible) {
        Match best = null;
        for (Map.Entry<Long, Point> e : where.entrySet()) {
            if (!eligible.test(e.getKey())) {
                continue;
            }
            long d = e.getValue().distSq(from);
            if (best == null || d < best.distSq() || (d == best.distSq() && e.getKey() < best.driverId())) {
                best = new Match(e.getKey(), d);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Counts eligible drivers whose cell equals the cell of p. */
    @Override
    public int countInCellOf(Point p, LongPredicate eligible) {
        Cell c = cellOf(p);
        int n = 0;
        for (Map.Entry<Long, Point> e : where.entrySet()) {
            if (eligible.test(e.getKey()) && cellOf(e.getValue()).equals(c)) {
                n++;
            }
        }
        return n;
    }

    /** Names the cell of p. */
    @Override
    public Cell cellOf(Point p) {
        return Cell.of(p, cellSize);
    }

    /** Returns the driver count. */
    @Override
    public int size() {
        return where.size();
    }
}
