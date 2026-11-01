package com.lld.tictactoe;

import static com.lld.tictactoe.Games.config;
import static com.lld.tictactoe.Games.playAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Rejected;
import com.lld.tictactoe.MoveResult.Won;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Tests for undo and replay: undo puts back everything, and replay rebuilds the same game. */
class UndoReplayTest {

    /** Proves that undoing the winning move reopens the game and clears the winner, the winning line and the counters. */
    @Test
    void undoingTheWinningMoveReopensTheGame() {
        Game game = new Game(GameConfig.classic());
        assertInstanceOf(Won.class, playAll(game, "0,0", "1,0", "0,1", "1,1", "0,2"));

        Move undone = game.undo();

        assertEquals(new Cell(0, 2), undone.cell());
        assertEquals(Status.IN_PROGRESS, game.status());
        assertEquals("X", game.playerToMove().orElseThrow().id());
        assertEquals(List.of(), game.winningLine());
        assertEquals(Optional.empty(), game.winner());
        // The detector forgot the stone. Column 2 ends X(2,2), O(0,2), X(1,2). With a stale X count at (0,2) it would read 3 and "win".
        assertInstanceOf(Accepted.class, playAll(game, "2,2", "0,2", "1,2"));
        assertEquals(Status.IN_PROGRESS, game.status());
    }

    /** Proves that replaying a game's history gives an identical board, status and history. */
    @Test
    void replayRebuildsTheSameGame() {
        Game original = new Game(config(5, 4, 3));
        playAll(original, "0,0", "1,1", "2,2", "0,1", "1,2", "2,3", "0,2");

        Game replayed = Game.replay(original.config(), original.history());

        assertArrayEquals(original.board(), replayed.board());
        assertEquals(original.status(), replayed.status());
        assertEquals(original.playerToMove(), replayed.playerToMove());
        assertEquals(original.history(), replayed.history());
    }

    /** Proves that after random play and undoing every move, the game equals a fresh one. */
    @Test
    void randomPlayThenUndoEverythingLeavesAFreshGame() {
        SplittableRandom random = new SplittableRandom(7);
        for (int g = 0; g < 500; g++) {
            int n = 1 + random.nextInt(5);
            Game game = new Game(config(n, 1 + random.nextInt(n), 2 + random.nextInt(3)));
            RandomStrategy strategy = new RandomStrategy(random.split());
            while (game.status() == Status.IN_PROGRESS) {
                Cell c = strategy.choose(game);
                game.play(game.playerToMove().orElseThrow().id(), c.row(), c.col());
            }
            List<Move> log = game.history();
            Status status = game.status();
            Optional<Player> winner = game.winner();
            List<Cell> line = game.winningLine();

            while (!game.history().isEmpty()) {
                game.undo();
            }
            assertEquals(n * n, game.emptyCells().size());
            assertEquals(game.config().players().get(0), game.playerToMove().orElseThrow());
            assertEquals(Optional.empty(), game.winner());

            // Same game object, same moves: leftover counts would end it early or differently.
            for (Move m : log) {
                assertFalse(game.play(m.player().id(), m.cell().row(), m.cell().col()) instanceof Rejected,
                        () -> "game " + log + " diverged at " + m);
            }
            assertEquals(status, game.status());
            assertEquals(winner, game.winner());
            assertEquals(line, game.winningLine());
        }
    }

    /** Proves that undo on a game with no moves throws, because it is a caller bug. */
    @Test
    void undoOnAFreshGameIsACallerBug() {
        assertThrows(IllegalStateException.class, () -> new Game(GameConfig.classic()).undo());
    }

    /** Proves that replay refuses a log that contains an illegal move. */
    @Test
    void replayRejectsAnIllegalLog() {
        GameConfig config = GameConfig.classic();
        Player x = config.players().get(0);
        List<Move> twoXInARow = List.of(new Move(x, new Cell(0, 0)), new Move(x, new Cell(1, 1)));

        assertThrows(IllegalArgumentException.class, () -> Game.replay(config, twoXInARow));
    }

    /** Proves that replay refuses a move by a player who has the right id but a different symbol, which would be a forged log. */
    @Test
    void replayRejectsAMoveByAnImpostorWithTheRightId() {
        GameConfig config = GameConfig.classic();
        List<Move> forged = List.of(new Move(new Player("X", 'Z'), new Cell(0, 0)));

        assertThrows(IllegalArgumentException.class, () -> Game.replay(config, forged));
    }

    /** Proves that the history returned is a snapshot that does not grow when more moves are played. */
    @Test
    void historyIsASnapshot() {
        Game game = new Game(GameConfig.classic());
        List<Move> before = game.history();
        game.play("X", 0, 0);

        assertEquals(0, before.size());
        assertThrows(UnsupportedOperationException.class, () -> game.history().clear());
    }
}
