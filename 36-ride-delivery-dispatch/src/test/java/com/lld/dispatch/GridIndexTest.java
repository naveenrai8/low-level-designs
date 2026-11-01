package com.lld.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** v1: the grid index must give exactly the brute-force answer, including on cell edges, with negative coordinates and on ties. */
class GridIndexTest {

    /** Accepts every driver. */
    private static final java.util.function.LongPredicate ALL = id -> true;

    /** The worked example from the README: cell size 10, query at (9,5), driver A at (0,5) in the same cell, driver B at (10,5) one metre away across the edge. */
    @Test
    void ringSearchContinuesPastTheFirstNonEmptyRing() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(0, 5));
        g.upsert(2, new Point(10, 5));
        Match m = g.nearest(new Point(9, 5), ALL).orElseThrow();
        assertEquals(2, m.driverId());
        assertEquals(1, m.distSq());
    }

    /** The same example against the deliberately wrong stopping rule: it returns the far driver, which is the bug the README explains. */
    @Test
    void stoppingAtTheFirstNonEmptyRingPicksTheWrongDriver() {
        GridIndex bad = new GridIndex(10, StopRule.FIRST_NON_EMPTY_RING);
        bad.upsert(1, new Point(0, 5));
        bad.upsert(2, new Point(10, 5));
        Match m = bad.nearest(new Point(9, 5), ALL).orElseThrow();
        assertEquals(1, m.driverId());
        assertEquals(81, m.distSq());
    }

    /** Over many random worlds the wrong rule disagrees with brute force often, so the differential test below would catch it. */
    @Test
    void wrongRuleIsCaughtByTheDifferentialCheck() {
        Random rnd = new Random(7);
        int mismatches = 0;
        for (int w = 0; w < 300; w++) {
            GridIndex bad = new GridIndex(10, StopRule.FIRST_NON_EMPTY_RING);
            LinearIndex ref = new LinearIndex(10);
            for (int id = 1; id <= 300; id++) {
                Point p = new Point(rnd.nextInt(201) - 100, rnd.nextInt(201) - 100);
                bad.upsert(id, p);
                ref.upsert(id, p);
            }
            Point q = new Point(rnd.nextInt(201) - 100, rnd.nextInt(201) - 100);
            if (!bad.nearest(q, ALL).equals(ref.nearest(q, ALL))) {
                mismatches++;
            }
        }
        assertTrue(mismatches > 30, "mismatches: " + mismatches);
    }

    /** Thousands of seeded random worlds with updates, removals, a filter, negative coordinates and tiny cells that put many points on edges: the grid always equals brute force. */
    @Test
    void differentialAgainstBruteForce() {
        long[] sizes = {1, 3, 10, 100};
        Random rnd = new Random(2024);
        int queries = 0;
        for (int w = 0; w < 4000; w++) {
            long size = sizes[w % sizes.length];
            int span = (w % 2 == 0) ? 40 : 400;
            GridIndex g = new GridIndex(size);
            LinearIndex ref = new LinearIndex(size);
            int n = 1 + rnd.nextInt(25);
            for (int id = 1; id <= n; id++) {
                Point p = new Point(rnd.nextInt(2 * span + 1) - span, rnd.nextInt(2 * span + 1) - span);
                g.upsert(id, p);
                ref.upsert(id, p);
            }
            for (int step = 0; step < 6; step++) {
                int id = 1 + rnd.nextInt(n + 3);
                if (rnd.nextInt(4) == 0) {
                    assertEquals(ref.remove(id), g.remove(id));
                } else {
                    Point p = new Point(rnd.nextInt(2 * span + 1) - span, rnd.nextInt(2 * span + 1) - span);
                    g.upsert(id, p);
                    ref.upsert(id, p);
                }
                int mod = 2 + rnd.nextInt(3);
                Point q = new Point(rnd.nextInt(2 * span + 1) - span, rnd.nextInt(2 * span + 1) - span);
                assertEquals(ref.nearest(q, d -> d % mod != 0), g.nearest(q, d -> d % mod != 0), "world " + w + " step " + step);
                assertEquals(ref.size(), g.size());
                queries++;
            }
        }
        assertEquals(24_000, queries);
    }

    /** Negative coordinates land in the cell below zero: x=-1 is in cell -1, not in cell 0 (floorDiv, not truncating division). */
    @Test
    void negativeCoordinatesUseFloorDivision() {
        assertEquals(new Cell(-1, -1), Cell.of(new Point(-1, -1), 10));
        assertEquals(new Cell(-1, 0), Cell.of(new Point(-10, 0), 10));
        assertEquals(new Cell(-2, 0), Cell.of(new Point(-11, 9), 10));
        assertEquals(new Cell(0, 0), Cell.of(new Point(9, 9), 10));
        assertEquals(new Cell(1, 0), Cell.of(new Point(10, 9), 10));
    }

    /** A query just left of the origin must find a driver just right of it (they are one metre apart but in different cells). */
    @Test
    void nearestAcrossTheOriginWorks() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(0, 0));
        g.upsert(2, new Point(-9, -9));
        assertEquals(1, g.nearest(new Point(-1, -1), ALL).orElseThrow().driverId());
        assertEquals(2, g.nearest(new Point(-8, -8), ALL).orElseThrow().driverId());
    }

    /** Two drivers at exactly the same distance: the smaller id wins, whatever order they were added in, and whichever cell they are in. */
    @Test
    void tieGoesToTheSmallerDriverId() {
        GridIndex g = new GridIndex(10);
        g.upsert(9, new Point(5, 0));
        g.upsert(3, new Point(-5, 0));
        g.upsert(7, new Point(0, 5));
        assertEquals(3, g.nearest(new Point(0, 0), ALL).orElseThrow().driverId());
        GridIndex h = new GridIndex(10);
        h.upsert(3, new Point(-5, 0));
        h.upsert(7, new Point(0, 5));
        h.upsert(9, new Point(5, 0));
        assertEquals(3, h.nearest(new Point(0, 0), ALL).orElseThrow().driverId());
    }

    /** A location update moves a driver to its new cell and leaves nothing behind in the old one. */
    @Test
    void locationUpdateMovesBetweenCells() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(5, 5));
        assertEquals(1, g.countInCellOf(new Point(0, 0), ALL));
        g.upsert(1, new Point(25, 5));
        assertEquals(0, g.countInCellOf(new Point(0, 0), ALL));
        assertEquals(1, g.countInCellOf(new Point(20, 0), ALL));
        assertEquals(1, g.size());
        assertEquals(1, g.nearest(new Point(0, 0), ALL).orElseThrow().driverId());
        assertTrue(g.remove(1));
        assertFalse(g.remove(1));
        assertEquals(Optional.empty(), g.nearest(new Point(0, 0), ALL));
    }

    /** A filter that rejects everyone gives empty, and the search ends even though the rings are endless. */
    @Test
    void emptyAndFullyFilteredTerminate() {
        GridIndex g = new GridIndex(5);
        assertEquals(Optional.empty(), g.nearest(new Point(0, 0), ALL));
        g.upsert(1, new Point(900, 900));
        assertEquals(Optional.empty(), g.nearest(new Point(0, 0), id -> false));
        assertEquals(1, g.nearest(new Point(-900, -900), ALL).orElseThrow().driverId());
    }

    /** Far-apart extremes of the allowed range give an exact squared distance with no overflow, and out-of-range input is refused. */
    @Test
    void extremeCoordinatesAreExactAndOutOfRangeIsRefused() {
        Point a = new Point(-Point.LIMIT, -Point.LIMIT);
        Point b = new Point(Point.LIMIT, Point.LIMIT);
        assertEquals(8_000_000_000_000_000_000L, a.distSq(b));
        GridIndex g = new GridIndex(1_000_000);
        g.upsert(1, a);
        assertEquals(8_000_000_000_000_000_000L, g.nearest(b, ALL).orElseThrow().distSq());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new Point(Point.LIMIT + 1, 0)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new GridIndex(0)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new GridIndex(1_000_001)).code());
    }

    /** The integer square root rounds up and is exact on perfect squares. */
    @Test
    void integerSqrtRoundsUp() {
        assertEquals(0, Geometry.isqrtCeil(0));
        assertEquals(1, Geometry.isqrtCeil(1));
        assertEquals(2, Geometry.isqrtCeil(2));
        assertEquals(2, Geometry.isqrtCeil(4));
        assertEquals(3, Geometry.isqrtCeil(5));
        assertEquals(3_000_000_000L, Geometry.isqrtCeil(9_000_000_000_000_000_000L));
        assertNotEquals(0, Geometry.isqrtCeil(Long.MAX_VALUE));
    }

    /** Exhaustive check of the stopping bound: for sizes 1 to 4, every rider offset, riders in positive and negative cells, rings 0 to 2, every integer point outside those rings is at least the bound away, and some point is exactly the bound away (so the bound is safe and tight). */
    @Test
    void lowerBoundIsSafeAndTightForEveryOffset() {
        for (long size = 1; size <= 4; size++) {
            for (long ox = 0; ox < size; ox++) {
                for (long oy = 0; oy < size; oy++) {
                    for (long[] shift : new long[][] {{0, 0}, {-3, 2}, {2, -4}}) {
                        Point rider = new Point(shift[0] * size + ox, shift[1] * size + oy);
                        Cell rc = Cell.of(rider, size);
                        for (long r = 0; r <= 2; r++) {
                            long bound = GridIndex.lowerBound(size, rider, r);
                            long min = Long.MAX_VALUE;
                            for (long x = rider.x() - (r + 3) * size; x <= rider.x() + (r + 3) * size; x++) {
                                for (long y = rider.y() - (r + 3) * size; y <= rider.y() + (r + 3) * size; y++) {
                                    Point p = new Point(x, y);
                                    Cell c = Cell.of(p, size);
                                    if (Math.max(Math.abs(c.cx() - rc.cx()), Math.abs(c.cy() - rc.cy())) > r) {
                                        min = Math.min(min, p.distSq(rider));
                                    }
                                }
                            }
                            assertEquals(bound * bound, min, "size " + size + " rider " + rider + " ring " + r);
                        }
                    }
                }
            }
        }
    }

    /** A tie exactly at the bound must not stop the search: driver 5 (ring 1) and driver 1 (ring 2) are both at squared distance 121, and the smaller id 1 must win. */
    @Test
    void tieExactlyAtTheBoundKeepsSearching() {
        GridIndex g = new GridIndex(10);
        g.upsert(5, new Point(9, 16));
        g.upsert(1, new Point(20, 5));
        // far-away drivers in many cells, so the ring search is used instead of the scan-everything fallback
        for (int i = 0; i < 40; i++) {
            g.upsert(100 + i, new Point(1_000 + i * 10, 1_000));
        }
        Match m = g.nearest(new Point(9, 5), ALL).orElseThrow();
        assertEquals(121, m.distSq());
        assertEquals(1, m.driverId());
    }

    /** The search really stops early: with the nearest driver in the rider's own cell and 2000 drivers far away, only one cell and one entry are touched. */
    @Test
    void searchStopsEarlyAndNeverVisitsAFarCrowd() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(5, 5));
        for (int i = 0; i < 2000; i++) {
            g.upsert(100 + i, new Point(5_000 + (i % 50) * 10, 5_000 + (i / 50) * 10));
        }
        g.resetProbe();
        assertEquals(1, g.nearest(new Point(5, 5), ALL).orElseThrow().driverId());
        assertEquals(1, g.cellLookups());
        assertEquals(1, g.entriesScanned());
        // a rider one ring away still stays far below a full scan
        g.resetProbe();
        assertEquals(1, g.nearest(new Point(15, 5), ALL).orElseThrow().driverId());
        assertTrue(g.cellLookups() <= 25, "lookups " + g.cellLookups());
        assertTrue(g.entriesScanned() <= 3);
    }

    /** Empty cells are dropped: after moves and removals the number of cells equals the number of occupied cells, and the driver is in the grid exactly once. */
    @Test
    void emptyCellsAreDropped() {
        GridIndex g = new GridIndex(10);
        g.upsert(1, new Point(5, 5));
        g.upsert(2, new Point(6, 6));
        assertEquals(1, g.cellCount());
        g.upsert(1, new Point(55, 5));
        assertEquals(2, g.cellCount());
        g.upsert(2, new Point(56, 6));
        assertEquals(1, g.cellCount());
        g.remove(1);
        g.remove(2);
        assertEquals(0, g.cellCount());
        assertFalse(g.contains(1));
    }

    /** Long.MIN_VALUE must be refused too (its absolute value is still negative), and the limits themselves are allowed. */
    @Test
    void minValueCoordinateIsRefused() {
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new Point(Long.MIN_VALUE, 0)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new Point(0, Long.MIN_VALUE)).code());
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new Point(-Point.LIMIT - 1, 0)).code());
        new Point(-Point.LIMIT, Point.LIMIT);
    }

    /** The integer square root of Long.MAX_VALUE is exactly 3,037,000,500 and does not overflow on the way. */
    @Test
    void integerSqrtOfLongMaxIsExact() {
        assertEquals(3_037_000_500L, Geometry.isqrtCeil(Long.MAX_VALUE));
        assertEquals(3_037_000_499L, Geometry.isqrtCeil(3_037_000_499L * 3_037_000_499L));
    }

    /** The brute-force reference refuses a bad cell size like the grid does. */
    @Test
    void linearIndexValidatesItsCellSize() {
        assertEquals(DispatchException.Code.OUT_OF_RANGE, assertThrows(DispatchException.class, () -> new LinearIndex(0)).code());
    }
}
