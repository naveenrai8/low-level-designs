package com.lld.tictactoe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * O(1) win check for K == N. Each player has a count per row, per column and per diagonal; a line is won when one player's count for it reaches N.
 *
 * <p>Counts are per player, so this works for any number of players. The well-known two-player shortcut (one {@code int} per line, +1 for X and −1 for O, win at ±N) is the special case P = 2. It silently breaks for three players: ±1 has only two signs, so two players share one, and a row like X·A·X sums to 3 and reads as a win.
 *
 * <p>Only valid when K == N. With K < N a run of K inside a longer row doesn't make the row count N, so {@link DirectionalScan} handles that case.
 *
 * <p>Memory: (2N + 2) ints per player.
 */
final class LineCounters implements WinDetector {

    private final int n;
    private final int[][] rows; // [player][row]
    private final int[][] cols; // [player][col]
    private final int[] diag; // [player], cells where row == col
    private final int[] anti; // [player], cells where row + col == n - 1

    /** Sets up one counter set per player: rows, columns and the two diagonals. */
    LineCounters(int size, int players) {
        this.n = size;
        this.rows = new int[players][size];
        this.cols = new int[players][size];
        this.diag = new int[players];
        this.anti = new int[players];
    }

    /** Adds a stone to the counts for its row, column and (if it sits on one) diagonals. */
    @Override
    public void place(int row, int col, int player) {
        add(row, col, player, +1);
    }

    /** Takes a stone away from the counts, which is how undo works. */
    @Override
    public void remove(int row, int col, int player) {
        add(row, col, player, -1);
    }

    /** Shared code for place and remove: moves the four counters by +1 or -1. The assert catches a removal of a stone that was never placed. */
    private void add(int row, int col, int player, int delta) {
        rows[player][row] += delta;
        cols[player][col] += delta;
        if (row == col) {
            diag[player] += delta;
        }
        if (row + col == n - 1) {
            anti[player] += delta;
        }
        assert rows[player][row] >= 0 && cols[player][col] >= 0 && diag[player] >= 0 && anti[player] >= 0
                : "removed a stone that was never placed";
    }

    /** Only checks the lines that pass through the cell just played, because a new win must include the newest stone. Returns that line or empty. */
    @Override
    public Optional<List<Cell>> winningLine(int row, int col, int player) {
        if (rows[player][row] == n) {
            return Optional.of(line(i -> new Cell(row, i)));
        }
        if (cols[player][col] == n) {
            return Optional.of(line(i -> new Cell(i, col)));
        }
        if (row == col && diag[player] == n) {
            return Optional.of(line(i -> new Cell(i, i)));
        }
        if (row + col == n - 1 && anti[player] == n) {
            return Optional.of(line(i -> new Cell(i, n - 1 - i)));
        }
        return Optional.empty();
    }

    /** Builds the list of N cells of one line, given a function from index to cell. */
    private List<Cell> line(IntFunction<Cell> cellAt) {
        List<Cell> cells = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cells.add(cellAt.apply(i));
        }
        return cells;
    }
}
