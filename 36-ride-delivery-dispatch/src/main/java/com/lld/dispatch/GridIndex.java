package com.lld.dispatch;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.LongPredicate;

/**
 * Uniform grid index with an expanding ring search.
 * <p>A <b>ring</b> is the set of cells exactly r steps away from the query cell (steps counted as the larger of the column and row difference). Ring 0 is the query cell, ring 1 is the 8 cells around it, ring 2 is the 16 cells around those.
 * <p>Stopping rule: after scanning ring r, every unseen driver is in ring r+1 or farther, so it is at least {@code r*size + m} metres away. Here m is the smallest number of metres from the query to a point just outside its own cell on any of the four sides (m is 1 to ceil(size/2)). If the best distance found is strictly below that bound, nothing unseen can win or tie, so stop. It must be strictly below: an unseen driver exactly at the bound could tie and have a smaller id.
 * <p>Cost: ring r has 8r cells. With rho drivers per cell the nearest one is usually within about 1/sqrt(rho) rings, so a query reads about (2r+1)^2 cells: a dense city is O(1) per query. A sparse area costs more rings; once 8r exceeds the number of occupied cells the search just visits every occupied cell, which also guarantees it terminates.
 */
public final class GridIndex implements DriverIndex {

    private final long size;
    private final StopRule rule;
    private final Map<Cell, TreeSet<Long>> cells = new HashMap<>();
    private final Map<Long, Point> where = new LinkedHashMap<>();
    private long cellLookups;
    private long entriesScanned;

    /**
     * Builds an empty grid with the correct stopping rule.
     *
     * @param cellSize the cell side in metres, 1..1,000,000; too small means many empty rings, too big means long lists per cell
     */
    public GridIndex(long cellSize) {
        this(cellSize, StopRule.CORRECT);
    }

    /**
     * Builds an empty grid with a chosen stopping rule. Package-private on purpose: only tests may pick the wrong rule to show the bug.
     *
     * @param cellSize the cell side in metres, 1..1,000,000
     * @param rule when the ring search stops
     */
    GridIndex(long cellSize, StopRule rule) {
        if (cellSize < 1 || cellSize > 1_000_000L) {
            throw new DispatchException(DispatchException.Code.OUT_OF_RANGE, "cell size must be 1..1000000: " + cellSize);
        }
        this.size = cellSize;
        this.rule = Objects.requireNonNull(rule);
    }

    /** Puts the driver in the cell of its new position, removing it from its old cell first so it is never in two cells. */
    @Override
    public void upsert(long driverId, Point p) {
        Objects.requireNonNull(p);
        Point old = where.get(driverId);
        Cell nc = Cell.of(p, size);
        if (old != null) {
            Cell oc = Cell.of(old, size);
            if (!oc.equals(nc)) {
                dropFromCell(oc, driverId);
            }
        }
        cells.computeIfAbsent(nc, c -> new TreeSet<>()).add(driverId);
        where.put(driverId, p);
    }

    /** Removes the driver from its cell and from the position table. */
    @Override
    public boolean remove(long driverId) {
        Point old = where.remove(driverId);
        if (old == null) {
            return false;
        }
        dropFromCell(Cell.of(old, size), driverId);
        return true;
    }

    /** Deletes one id from a cell and drops the cell when it becomes empty, so the number of cells stays equal to the number of occupied cells. */
    private void dropFromCell(Cell c, long driverId) {
        TreeSet<Long> set = cells.get(c);
        set.remove(driverId);
        if (set.isEmpty()) {
            cells.remove(c);
        }
    }

    /** Expands rings around the query cell until the stopping rule says no farther driver can be better. */
    @Override
    public Optional<Match> nearest(Point from, LongPredicate eligible) {
        Objects.requireNonNull(eligible);
        Cell qc = Cell.of(from, size);
        Best best = new Best();
        for (long r = 0;; r++) {
            if (8 * r > cells.size()) {
                // the rings now hold more cells than exist: just look at every occupied cell
                for (TreeSet<Long> set : cells.values()) {
                    scan(set, from, eligible, best);
                }
                break;
            }
            scanRing(qc, r, from, eligible, best);
            if (best.found) {
                if (rule == StopRule.FIRST_NON_EMPTY_RING) {
                    break;
                }
                long lower = lowerBound(size, from, r);
                if (best.distSq < Math.multiplyExact(lower, lower)) {
                    break;
                }
            }
        }
        return best.found ? Optional.of(new Match(best.id, best.distSq)) : Optional.empty();
    }

    /**
     * The least distance in metres from a point to any point in ring r+1 or beyond. Walk out to the nearest side of the point's own cell (ox+1 to the left, size-ox to the right, same for y), then r more whole cells.
     *
     * @param size the cell side
     * @param from the query point
     * @param r the last ring that was scanned
     * @return the lower bound, tight: some integer point in ring r+1 is exactly this far
     */
    static long lowerBound(long size, Point from, long r) {
        long ox = Math.floorMod(from.x(), size);
        long oy = Math.floorMod(from.y(), size);
        long m = Math.min(Math.min(size - ox, ox + 1), Math.min(size - oy, oy + 1));
        return r * size + m;
    }

    /** Test probe: true if the driver is in the grid. */
    boolean contains(long driverId) {
        return where.containsKey(driverId);
    }

    /** Test probe: how many cells currently hold at least one driver. */
    int cellCount() {
        return cells.size();
    }

    /** Test probe: cells looked up by searches since the last reset. */
    long cellLookups() {
        return cellLookups;
    }

    /** Test probe: driver entries examined by searches since the last reset. */
    long entriesScanned() {
        return entriesScanned;
    }

    /** Test probe: zeroes the two search counters. */
    void resetProbe() {
        cellLookups = 0;
        entriesScanned = 0;
    }

    /** Mutable holder for the best candidate seen so far. */
    private static final class Best {
        boolean found;
        long id;
        long distSq;
    }

    /** Visits every cell of ring r: the top and bottom rows, then the left and right columns without their corners. */
    private void scanRing(Cell qc, long r, Point from, LongPredicate eligible, Best best) {
        if (r == 0) {
            scanCell(qc.cx(), qc.cy(), from, eligible, best);
            return;
        }
        for (long dx = -r; dx <= r; dx++) {
            scanCell(qc.cx() + dx, qc.cy() - r, from, eligible, best);
            scanCell(qc.cx() + dx, qc.cy() + r, from, eligible, best);
        }
        for (long dy = -r + 1; dy <= r - 1; dy++) {
            scanCell(qc.cx() - r, qc.cy() + dy, from, eligible, best);
            scanCell(qc.cx() + r, qc.cy() + dy, from, eligible, best);
        }
    }

    /** Looks up one cell by column and row and scans its drivers if it is occupied. */
    private void scanCell(long cx, long cy, Point from, LongPredicate eligible, Best best) {
        cellLookups++;
        TreeSet<Long> set = cells.get(new Cell(cx, cy));
        if (set != null) {
            scan(set, from, eligible, best);
        }
    }

    /** Offers each eligible driver of a cell to the best-so-far, with the smaller id winning an exact tie. */
    private void scan(TreeSet<Long> set, Point from, LongPredicate eligible, Best best) {
        for (long id : set) {
            entriesScanned++;
            if (!eligible.test(id)) {
                continue;
            }
            long d = where.get(id).distSq(from);
            if (!best.found || d < best.distSq || (d == best.distSq && id < best.id)) {
                best.found = true;
                best.id = id;
                best.distSq = d;
            }
        }
    }

    /** Counts eligible drivers in the same cell as p. */
    @Override
    public int countInCellOf(Point p, LongPredicate eligible) {
        TreeSet<Long> set = cells.get(Cell.of(p, size));
        int n = 0;
        if (set != null) {
            for (long id : set) {
                if (eligible.test(id)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Names the cell of p. */
    @Override
    public Cell cellOf(Point p) {
        return Cell.of(p, size);
    }

    /** Returns the driver count. */
    @Override
    public int size() {
        return where.size();
    }
}
