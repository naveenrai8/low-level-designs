package com.lld.tictactoe;

import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Draw;
import com.lld.tictactoe.MoveResult.Rejected;
import com.lld.tictactoe.MoveResult.Won;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perfect play by full game-tree search: negamax with alpha-beta pruning, using the game's own {@link Game#play} / {@link Game#undo} on a private copy.
 *
 * <ul>
 *   <li><b>Score</b>, from the mover's side: a win is {@code 10 − ply}, a loss {@code −(10 − ply)}, a draw 0. The ply term prefers the fastest win and the slowest loss. 10 exceeds the 9 plies of a 3×3 game, so every win scores above every draw.
 *   <li><b>Ties</b> go to the first best cell in row-major order, so play is deterministic.
 *   <li><b>Scope</b>: two players and N ≤ 3 only. A 4×4 tree is too big to search fully; that needs depth limits and a heuristic (README, F2). Larger configs throw.
 *   <li><b>Memo</b>: the chosen cell is cached per (config, board). Search is deterministic, so the cache never changes an answer; it only stops 1,000 test games from re-searching the same opening. At most 5,478 positions exist, which bounds it.
 * </ul>
 *
 * <p>Thread-safe: each call searches its own copy, and the memo is a {@link ConcurrentHashMap} whose loader never touches the map.
 */
public final class MinimaxStrategy implements MoveStrategy {

    private static final int WIN = 10;
    private static final int INFINITY = 1_000;

    /** A cache key: the same rules plus the same board text always give the same best move. */
    private record Key(GameConfig config, String board) {}

    private final ConcurrentHashMap<Key, Cell> memo = new ConcurrentHashMap<>();

    /** Returns the best cell for the player to move. Throws if the board is bigger than 3x3, has more than 2 players, or the game is over. Answers are cached. */
    @Override
    public Cell choose(Game game) {
        GameConfig config = game.config();
        if (config.players().size() != 2 || config.size() > 3) {
            throw new IllegalArgumentException(
                    "minimax is exhaustive: supported for 2 players on boards up to 3x3, got "
                            + config.players().size() + " players on " + config.size() + "x" + config.size());
        }
        if (game.status() != Status.IN_PROGRESS) {
            throw new IllegalStateException("game is over: " + game.status());
        }
        return memo.computeIfAbsent(new Key(config, game.render()), k -> search(game.copy()));
    }

    /** Tries every empty cell and keeps the best one. A later cell must score strictly higher to replace it, so ties go to the earlier cell. */
    private Cell search(Game scratch) {
        int alpha = -INFINITY;
        Cell best = null;
        for (Cell cell : scratch.emptyCells()) {
            int score = scoreOf(scratch, cell, 1, alpha, INFINITY);
            if (score > alpha) { // strict: earlier cells win ties
                alpha = score;
                best = cell;
            }
        }
        return best;
    }

    /** Plays {@code cell}, scores the result for the player who played it, and undoes it. */
    private int scoreOf(Game game, Cell cell, int ply, int alpha, int beta) {
        String mover = game.playerToMove().orElseThrow().id();
        MoveResult result = game.play(mover, cell.row(), cell.col());
        try {
            return switch (result) {
                case Won w -> WIN - ply;
                case Draw d -> 0;
                case Accepted a -> -negamax(game, ply + 1, -beta, -alpha);
                case Rejected r -> throw new IllegalStateException("search tried an illegal move: " + r.reason());
            };
        } finally {
            game.undo();
        }
    }

    /** Best score for the player to move, within the window (alpha, beta). */
    private int negamax(Game game, int ply, int alpha, int beta) {
        int best = -INFINITY;
        for (Cell cell : game.emptyCells()) {
            best = Math.max(best, scoreOf(game, cell, ply, alpha, beta));
            alpha = Math.max(alpha, best);
            if (alpha >= beta) {
                break; // the opponent already has a better option elsewhere: prune
            }
        }
        return best;
    }
}
