package com.lld.spreadsheet;

/**
 * The size of a sheet: always 26 columns (A to Z) and a configurable number of rows (1 to rows).
 *
 * @param rows how many rows the sheet has; ids run from A1 to Z{rows}
 */
public record Grid(int rows) {

    /** The number of columns, fixed at 26 (letters A to Z). */
    public static final int COLS = 26;

    /** The classic size from the brief: A1 to Z99. */
    public static final Grid DEFAULT = new Grid(99);

    /**
     * Rejects a sheet with no rows.
     */
    public Grid {
        if (rows < 1) {
            throw new IllegalArgumentException("rows must be at least 1, got " + rows);
        }
    }

    /**
     * Says whether a zero-based column and a one-based row land inside this sheet.
     *
     * @param col zero-based column (0 is A)
     * @param row one-based row
     * @return true when the cell exists in this grid
     */
    public boolean contains(int col, int row) {
        return col >= 0 && col < COLS && row >= 1 && row <= rows;
    }
}
