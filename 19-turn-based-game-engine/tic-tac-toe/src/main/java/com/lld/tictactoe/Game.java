package com.lld.tictactoe;

import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Draw;
import com.lld.tictactoe.MoveResult.Reason;
import com.lld.tictactoe.MoveResult.Rejected;
import com.lld.tictactoe.MoveResult.Won;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One game of N×N, K-in-a-row, for two or more players.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #play} never throws for a rule violation: it returns {@link Rejected} and leaves the game unchanged. It throws only for caller bugs (unknown player id).
 *   <li>Every accepted move is appended to a log. {@link #undo} reverses the last one exactly, including a winning or drawing move; {@link #replay} rebuilds a game from a log.
 *   <li>Accessors return copies, so callers can't mutate the board behind the game's back.
 * </ul>
 *
 * <h2>Concurrency</h2>
 * <p>Not thread-safe, by design: a game is confined to one thread or guarded by its owner. {@link GameRegistry} is the thread-safe front door, with one lock per game.
 */
public final class Game {

    /** The symbol {@link #board()} uses for an empty cell. */
    public static final char EMPTY = '.';

    private static final int NONE = -1;

    private final GameConfig config;
    private final WinDetector detector;
    private final Map<String, Integer> seatById = new HashMap<>();
    private final int[][] grid; // seat index of the owner, or NONE
    private final List<Move> history = new ArrayList<>();

    /** Test seam: runs after the turn check passes, before the board changes. */
    private final Runnable afterTurnCheck;

    private int toMove; // seat index
    private Status status = Status.IN_PROGRESS;
    private int winner = NONE;
    private List<Cell> winningLine = List.of();

    /** Creates a game with an empty board, picking the win detector that suits the config. */
    public Game(GameConfig config) {
        this(config, WinDetector.forConfig(Objects.requireNonNull(config, "config")), () -> {});
    }

    /** Lets concurrency tests pause a move inside its race window (see GameRegistry). */
    Game(GameConfig config, Runnable afterTurnCheck) {
        this(config, WinDetector.forConfig(Objects.requireNonNull(config, "config")), afterTurnCheck);
    }

    /** Does the real set-up for both public constructors: the empty grid and the seat lookup by player id. */
    private Game(GameConfig config, WinDetector detector, Runnable afterTurnCheck) {
        this.afterTurnCheck = Objects.requireNonNull(afterTurnCheck, "afterTurnCheck");
        this.config = Objects.requireNonNull(config, "config");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.grid = new int[config.size()][config.size()];
        for (int[] row : grid) {
            Arrays.fill(row, NONE);
        }
        List<Player> players = config.players();
        for (int i = 0; i < players.size(); i++) {
            seatById.put(players.get(i).id(), i);
        }
    }

    /**
     * Rebuilds a game from a move log.
     *
     * @throws IllegalArgumentException if any move is refused, or its player isn't exactly the configured player with that id (same id, different symbol is a forged log)
     */
    public static Game replay(GameConfig config, List<Move> moves) {
        Game game = new Game(config);
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            Player seated = config.players().get(game.seatOf(m.player().id()));
            if (!seated.equals(m.player())) {
                throw new IllegalArgumentException("move " + i + " " + m + " is not by the configured " + seated);
            }
            if (game.play(m.player().id(), m.cell().row(), m.cell().col()) instanceof Rejected r) {
                throw new IllegalArgumentException("move " + i + " " + m + " is illegal: " + r.reason());
            }
        }
        return game;
    }

    /**
     * Places the player's symbol at (row, col).
     *
     * @throws IllegalArgumentException if {@code playerId} isn't in this game (a caller bug)
     */
    public MoveResult play(String playerId, int row, int col) {
        int seat = seatOf(playerId);
        if (status != Status.IN_PROGRESS) {
            return new Rejected(Reason.GAME_OVER);
        }
        if (seat != toMove) {
            return new Rejected(Reason.NOT_YOUR_TURN);
        }
        afterTurnCheck.run(); // the race window: another play() here also passes the turn check
        if (!inBounds(row, col)) {
            return new Rejected(Reason.OUT_OF_BOUNDS);
        }
        if (grid[row][col] != NONE) {
            return new Rejected(Reason.OCCUPIED);
        }

        Player player = config.players().get(seat);
        grid[row][col] = seat;
        detector.place(row, col, seat);
        history.add(new Move(player, new Cell(row, col)));

        Optional<List<Cell>> line = detector.winningLine(row, col, seat);
        if (line.isPresent()) {
            status = Status.WON;
            winner = seat;
            winningLine = List.copyOf(line.get());
            return new Won(player, winningLine);
        }
        if (history.size() == config.size() * config.size()) {
            status = Status.DRAW;
            return new Draw();
        }
        toMove = (toMove + 1) % config.players().size();
        return new Accepted(config.players().get(toMove));
    }

    /**
     * Reverses the last accepted move exactly: the cell, the detector's counts, whose turn it is, and the status (undoing a winning move puts the game back in progress).
     *
     * @return the move that was undone
     * @throws IllegalStateException if no move has been played
     */
    public Move undo() {
        if (history.isEmpty()) {
            throw new IllegalStateException("nothing to undo");
        }
        Move last = history.remove(history.size() - 1);
        int seat = seatById.get(last.player().id());
        Cell cell = last.cell();
        grid[cell.row()][cell.col()] = NONE;
        detector.remove(cell.row(), cell.col(), seat);
        toMove = seat;
        status = Status.IN_PROGRESS;
        winner = NONE;
        winningLine = List.of();
        return last;
    }

    /** A detached copy with the same history. */
    public Game copy() {
        return replay(config, history);
    }

    /** Gives back the rules this game was created with. */
    public GameConfig config() {
        return config;
    }

    /** Gives back the current lifecycle state: in progress, won or draw. */
    public Status status() {
        return status;
    }

    /** Whose turn it is; empty once the game is over. */
    public Optional<Player> playerToMove() {
        return status == Status.IN_PROGRESS ? Optional.of(config.players().get(toMove)) : Optional.empty();
    }

    /** Gives back the winner, or empty while the game is in progress or after a draw. */
    public Optional<Player> winner() {
        return winner == NONE ? Optional.empty() : Optional.of(config.players().get(winner));
    }

    /** The cells of the winning line; empty unless {@link #status()} is {@code WON}. */
    public List<Cell> winningLine() {
        return winningLine;
    }

    /** The move log, oldest first. A snapshot: later moves don't show up in it. */
    public List<Move> history() {
        return List.copyOf(history);
    }

    /** Empty cells in row-major order (the order strategies break ties in). */
    public List<Cell> emptyCells() {
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid.length; c++) {
                if (grid[r][c] == NONE) {
                    cells.add(new Cell(r, c));
                }
            }
        }
        return cells;
    }

    /** A copy of the board as symbols, with {@link #EMPTY} for free cells. */
    public char[][] board() {
        char[][] board = new char[grid.length][grid.length];
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid.length; c++) {
                board[r][c] = grid[r][c] == NONE ? EMPTY : config.players().get(grid[r][c]).symbol();
            }
        }
        return board;
    }

    /** The board as text, one line per row, e.g. {@code "XO.\n.X.\n..O"}. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (char[] row : board()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(row);
        }
        return sb.toString();
    }

    /** Same as {@link #render()}, so printing a game shows its board. */
    @Override
    public String toString() {
        return render();
    }

    /** Looks up the seat number for a player id. Throws {@link IllegalArgumentException} for an unknown id (a caller bug) and {@link NullPointerException} for null. */
    private int seatOf(String playerId) {
        Integer seat = seatById.get(Objects.requireNonNull(playerId, "playerId"));
        if (seat == null) {
            throw new IllegalArgumentException("unknown player: " + playerId);
        }
        return seat;
    }

    /** True when (row, col) is a real cell on the board. */
    private boolean inBounds(int row, int col) {
        return row >= 0 && row < grid.length && col >= 0 && col < grid.length;
    }
}
