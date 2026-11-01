package com.lld.tictactoe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * General K-in-a-row check in O(K): from the cell just played, walk both ways along each of the 4 directions while the stones are the same player's, stopping after K − 1 steps each way. A run of ≥ K is a win.
 *
 * <p>Keeps its own ownership grid so it can be tested (and swapped) independently of {@link Game}.
 */
final class DirectionalScan implements WinDetector {

    private static final int NONE = -1;
    /** Row and column steps: horizontal, vertical, diagonal, anti-diagonal. */
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private final int n;
    private final int k;
    private final int[][] owner;

    /** Sets up an empty ownership grid for an n-by-n board and remembers the win length k. */
    DirectionalScan(int size, int winLength) {
        this.n = size;
        this.k = winLength;
        this.owner = new int[size][size];
        for (int[] row : owner) {
            Arrays.fill(row, NONE);
        }
    }

    /** Records that the player now owns this cell. The assert catches a double placement, which would be a bug in the caller. */
    @Override
    public void place(int row, int col, int player) {
        assert owner[row][col] == NONE : "cell already owned";
        owner[row][col] = player;
    }

    /** Clears the cell again, so a move can be undone. The assert catches removing a stone that belongs to someone else. */
    @Override
    public void remove(int row, int col, int player) {
        assert owner[row][col] == player : "removing another player's stone";
        owner[row][col] = NONE;
    }

    /** Checks the 4 directions through the cell just played. Returns the winning cells (at least K in a row), or empty if none. */
    @Override
    public Optional<List<Cell>> winningLine(int row, int col, int player) {
        for (int[] d : DIRECTIONS) {
            int back = run(row, col, -d[0], -d[1], player);
            int forward = run(row, col, d[0], d[1], player);
            if (back + 1 + forward >= k) {
                List<Cell> cells = new ArrayList<>(back + 1 + forward);
                for (int i = -back; i <= forward; i++) {
                    cells.add(new Cell(row + i * d[0], col + i * d[1]));
                }
                return Optional.of(cells);
            }
        }
        return Optional.empty();
    }

    /** Consecutive stones of {@code player} from (row, col) in one direction, at most K − 1. */
    private int run(int row, int col, int dr, int dc, int player) {
        int count = 0;
        int r = row + dr;
        int c = col + dc;
        while (count < k - 1 && r >= 0 && r < n && c >= 0 && c < n && owner[r][c] == player) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }
}
