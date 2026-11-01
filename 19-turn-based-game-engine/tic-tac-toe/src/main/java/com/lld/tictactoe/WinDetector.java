package com.lld.tictactoe;

import java.util.List;
import java.util.Optional;

/**
 * Incremental win check: told about every placement and removal, asked only about the line through the cell just played. That is the whole trick: a new win must include the newest stone, so there is never a reason to rescan the board.
 *
 * <p>Not thread-safe; owned by one {@link Game}.
 */
interface WinDetector {

    /** Records that a stone now sits on this cell for this player. */
    void place(int row, int col, int player);

    /** Records that a stone was taken off this cell, which undo needs. */
    void remove(int row, int col, int player);

    /** Called right after {@link #place}: a winning line through (row, col) for that player. */
    Optional<List<Cell>> winningLine(int row, int col, int player);

    /** O(1) counters when K == N (every line is a full row/column/diagonal), else an O(K) scan. */
    static WinDetector forConfig(GameConfig config) {
        return config.winLength() == config.size()
                ? new LineCounters(config.size(), config.players().size())
                : new DirectionalScan(config.size(), config.winLength());
    }
}
