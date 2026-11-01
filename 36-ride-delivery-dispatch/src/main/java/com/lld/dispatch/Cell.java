package com.lld.dispatch;

/**
 * One square of the grid, named by its column and row. Cell (0,0) covers x and y from 0 to size-1, cell (-1,0) covers x from -size to -1, and so on.
 *
 * @param cx the column index
 * @param cy the row index
 */
public record Cell(long cx, long cy) {

    /**
     * Finds the cell holding a point. It uses floorDiv, which rounds toward minus infinity, so x=-1 lands in cell -1. Plain division rounds toward zero and would put x=-1 in cell 0 next to x=0..size-1, so the cell would be twice as wide at the origin and the ring maths would break.
     *
     * @param p the point
     * @param size the cell side in metres
     * @return the cell containing p
     */
    public static Cell of(Point p, long size) {
        return new Cell(Math.floorDiv(p.x(), size), Math.floorDiv(p.y(), size));
    }
}
