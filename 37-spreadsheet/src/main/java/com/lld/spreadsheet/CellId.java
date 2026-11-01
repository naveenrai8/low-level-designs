package com.lld.spreadsheet;

/**
 * The address of one cell such as {@code B7}. Ordered column first, then row (A1, A2, ..., B1, B2, ...), so every sorted collection of cells iterates in the same order on every run.
 *
 * @param col zero-based column (0 is A)
 * @param row one-based row
 */
public record CellId(int col, int row) implements Comparable<CellId> {

    /**
     * Builds an id from a name like "B7" and checks that it exists in the grid.
     *
     * @param name the cell name, upper case letter then digits with no leading zero
     * @param grid the sheet that must contain it
     * @return the id
     * @throws IllegalArgumentException when the name is malformed or outside the grid
     */
    public static CellId parse(String name, Grid grid) {
        if (name == null || name.length() < 2 || name.charAt(0) < 'A' || name.charAt(0) > 'Z') {
            throw new IllegalArgumentException("bad cell id: " + name);
        }
        int row;
        try {
            row = Integer.parseInt(name.substring(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad cell id: " + name, e);
        }
        CellId id = new CellId(name.charAt(0) - 'A', row);
        if (!grid.contains(id.col, id.row) || !id.toString().equals(name)) {
            throw new IllegalArgumentException("bad cell id: " + name);
        }
        return id;
    }

    /**
     * Orders by column, then by row.
     *
     * @param o the other id
     * @return negative, zero or positive as usual
     */
    @Override
    public int compareTo(CellId o) {
        return col != o.col ? Integer.compare(col, o.col) : Integer.compare(row, o.row);
    }

    /**
     * Prints the spreadsheet name, for example "C12".
     *
     * @return the name
     */
    @Override
    public String toString() {
        return (char) ('A' + col) + Integer.toString(row);
    }
}
