package com.lld.tictactoe;

import static com.lld.tictactoe.Games.config;
import static com.lld.tictactoe.Games.playAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Draw;
import com.lld.tictactoe.MoveResult.Rejected;
import com.lld.tictactoe.MoveResult.Won;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Tests for {@link MinimaxStrategy}: it plays perfectly, is deterministic and refuses boards it cannot search. */
class MinimaxTest {

    private final MinimaxStrategy perfect = new MinimaxStrategy();

    /** Proves that two perfect players always draw on the classic board. */
    @Test
    void perfectAgainstPerfectIsADraw() {
        Game game = playOut(new Game(GameConfig.classic()), Map.of("X", perfect, "O", perfect));

        assertEquals(Status.DRAW, game.status());
    }

    /** Proves that perfect play never loses to a random player, whichever side moves first. */
    @Test
    void perfectNeverLosesToRandomAsEitherSide() {
        SplittableRandom seeds = new SplittableRandom(42);
        int perfectWins = 0;
        int[][] winsDraws = new int[2][2]; // [perfect is X ? 0 : 1][win, draw]
        for (int i = 0; i < 1_000; i++) {
            MoveStrategy random = new RandomStrategy(seeds.split());
            boolean perfectIsX = i % 2 == 0;
            Map<String, MoveStrategy> seats = perfectIsX
                    ? Map.of("X", perfect, "O", random)
                    : Map.of("X", random, "O", perfect);

            Game game = playOut(new Game(GameConfig.classic()), seats);

            String perfectId = perfectIsX ? "X" : "O";
            game.winner().ifPresent(w -> assertEquals(perfectId, w.id(), () -> "lost:\n" + game.render()));
            if (game.winner().isPresent()) {
                perfectWins++;
            }
            winsDraws[perfectIsX ? 0 : 1][game.winner().isPresent() ? 0 : 1]++;
        }
        assertNotEquals(0, perfectWins); // it also punishes mistakes, not just draws
        System.out.printf("perfect vs random, 500 games per side: as X %d W / %d D / 0 L, as O %d W / %d D / 0 L%n",
                winsDraws[0][0], winsDraws[0][1], winsDraws[1][0], winsDraws[1][1]);
    }

    /** Proves that a winning move is taken over a blocking move. */
    @Test
    void takesAnImmediateWinOverABlock() {
        Game game = new Game(GameConfig.classic());
        // X X . / O O . / . . .  — X to move: winning at (0,2) beats blocking at (1,2)
        playAll(game, "0,0", "1,0", "0,1", "1,1");

        assertEquals(new Cell(0, 2), perfect.choose(game));
    }

    /** Proves that when several wins are forced, the fastest one is chosen, which only works because the score subtracts the ply. */
    @Test
    void prefersTheFastestForcedWin() {
        Game game = new Game(GameConfig.classic());
        // O . . / X X . / . O .  — X to move
        playAll(game, "1,0", "0,0", "1,1", "2,1");

        // Check the position by brute force first (no pruning, no depth in the score).
        List<Cell> immediateWins = game.emptyCells().stream().filter(c -> winsAtOnce(game, c)).toList();
        List<Cell> forcedWins = game.emptyCells().stream().filter(c -> flatValue(game, c) == 1).toList();
        assertEquals(List.of(new Cell(1, 2)), immediateWins);
        assertEquals(new Cell(0, 2), forcedWins.get(0)); // a slower forced win, earlier in row-major order

        // A flat win score ties every forced win and the row-major tie-break picks (0,2). Scoring WIN − ply makes the immediate win strictly better.
        assertEquals(new Cell(1, 2), perfect.choose(game));
    }

    /** Proves that when the opponent threatens to win, the perfect player blocks it. */
    @Test
    void blocksAnImmediateLoss() {
        Game game = new Game(GameConfig.classic());
        // X . . / O O . / X . .  — X to move, no win available: must block (1,2)
        playAll(game, "0,0", "1,0", "2,0", "1,1");

        assertEquals(new Cell(1, 2), perfect.choose(game));
    }

    /** Proves that choosing a move does not change the game it was asked about (the search works on a copy). */
    @Test
    void doesNotMutateTheGameItIsAskedAbout() {
        Game game = new Game(GameConfig.classic());
        playAll(game, "1,1");
        String before = game.render();

        perfect.choose(game);

        assertEquals(before, game.render());
        assertEquals(1, game.history().size());
    }

    /** Proves that boards bigger than 3x3, or with more than 2 players, are refused instead of searching forever. */
    @Test
    void refusesBoardsTooBigToSearchExhaustively() {
        assertThrows(IllegalArgumentException.class, () -> perfect.choose(new Game(config(4, 4, 2))));
        assertThrows(IllegalArgumentException.class, () -> perfect.choose(new Game(config(3, 3, 3))));
    }

    /** Test helper: true if playing this cell wins the game at once. Plays and then undoes the move. */
    private static boolean winsAtOnce(Game game, Cell cell) {
        boolean won = game.play(game.playerToMove().orElseThrow().id(), cell.row(), cell.col()) instanceof Won;
        game.undo();
        return won;
    }

    /** Game value of playing {@code cell}, for the mover: +1 forced win, 0 draw, −1 forced loss. */
    private static int flatValue(Game game, Cell cell) {
        MoveResult result = game.play(game.playerToMove().orElseThrow().id(), cell.row(), cell.col());
        int value = switch (result) {
            case Won w -> 1;
            case Draw d -> 0;
            case Accepted a -> -game.emptyCells().stream().mapToInt(c -> flatValue(game, c)).max().orElseThrow();
            case Rejected r -> throw new AssertionError("brute force made an illegal move: " + r);
        };
        game.undo();
        return value;
    }

    /** Test helper: plays a whole game where each seat gets its own strategy, and returns the finished game. */
    private static Game playOut(Game game, Map<String, MoveStrategy> seats) {
        while (game.status() == Status.IN_PROGRESS) {
            String who = game.playerToMove().orElseThrow().id();
            Cell cell = seats.get(who).choose(game);
            game.play(who, cell.row(), cell.col());
        }
        return game;
    }
}
