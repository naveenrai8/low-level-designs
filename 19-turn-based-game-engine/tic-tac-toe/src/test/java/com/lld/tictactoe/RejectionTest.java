package com.lld.tictactoe;

import static com.lld.tictactoe.Games.playAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.tictactoe.MoveResult.Reason;
import com.lld.tictactoe.MoveResult.Rejected;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every rule violation is a value, and a refused move changes nothing. */
class RejectionTest {

    private static final int PLAYOUTS = 50;

    /** Supplies the refused-move cases: wrong turn, out of bounds, occupied cell, game over. */
    static Stream<Arguments> violations() {
        return Stream.of(
                //            moves before          who  row col  expected
                Arguments.of(new String[] {},           "O", 0, 0, Reason.NOT_YOUR_TURN),
                Arguments.of(new String[] {},           "X", 3, 0, Reason.OUT_OF_BOUNDS),
                Arguments.of(new String[] {},           "X", 0, -1, Reason.OUT_OF_BOUNDS),
                Arguments.of(new String[] {"1,1"},      "O", 1, 1, Reason.OCCUPIED),
                Arguments.of(new String[] {"1,1"},      "X", 0, 0, Reason.NOT_YOUR_TURN),
                // order of checks: NOT_YOUR_TURN is reported before OUT_OF_BOUNDS and OCCUPIED
                Arguments.of(new String[] {"1,1"},      "X", 9, 9, Reason.NOT_YOUR_TURN),
                Arguments.of(new String[] {"1,1"},      "X", 1, 1, Reason.NOT_YOUR_TURN),
                // X has won on the top row. Any further move is GAME_OVER, including from O, whose turn it would otherwise be
                Arguments.of(new String[] {"0,0", "1,0", "0,1", "1,1", "0,2"}, "O", 2, 2, Reason.GAME_OVER),
                // order of checks: GAME_OVER is reported before OUT_OF_BOUNDS
                Arguments.of(new String[] {"0,0", "1,0", "0,1", "1,1", "0,2"}, "O", 9, 9, Reason.GAME_OVER));
    }

    /** Proves each refused move returns the right reason and leaves the game exactly as it was, even in hidden state such as the win detector. */
    @ParameterizedTest(name = "{4} after {0}")
    @MethodSource("violations")
    void ruleViolationsAreRejectedWithoutSideEffects(String[] before, String who, int row, int col, Reason expected) {
        Game game = new Game(GameConfig.classic());
        playAll(game, before);
        char[][] boardBefore = game.board();
        int movesBefore = game.history().size();
        Status statusBefore = game.status();
        Optional<Player> toMoveBefore = game.playerToMove();

        MoveResult result = game.play(who, row, col);

        assertEquals(new Rejected(expected), result);
        assertArrayEquals(boardBefore, game.board());
        assertEquals(movesBefore, game.history().size());
        assertEquals(statusBefore, game.status());
        assertEquals(toMoveBefore, game.playerToMove());
        // The win detector is hidden state. A rejected move must not have touched it, so the game must play out exactly like a fresh replay of its log.
        Game untouched = Game.replay(game.config(), game.history());
        for (int seed = 0; seed < PLAYOUTS; seed++) {
            assertEquals(playout(untouched, seed), playout(game, seed), "playout " + seed);
        }
    }

    /** Plays the game to the end with a seeded random strategy, then undoes back to the start. */
    private static List<MoveResult> playout(Game game, int seed) {
        RandomStrategy strategy = new RandomStrategy(new SplittableRandom(seed));
        int start = game.history().size();
        List<MoveResult> results = new ArrayList<>();
        while (game.status() == Status.IN_PROGRESS) {
            Cell c = strategy.choose(game);
            results.add(game.play(game.playerToMove().orElseThrow().id(), c.row(), c.col()));
        }
        while (game.history().size() > start) {
            game.undo();
        }
        return results;
    }

    /** Proves that an unknown player id throws, since it is a caller bug and not a normal refusal. */
    @Test
    void unknownPlayerIsACallerBugNotARejection() {
        Game game = new Game(GameConfig.classic());

        assertThrows(IllegalArgumentException.class, () -> game.play("mallory", 0, 0));
    }

    /** Proves that {@code board()} returns a copy, so changing it does not change the game. */
    @Test
    void boardIsACopy() {
        Game game = new Game(GameConfig.classic());
        game.board()[0][0] = 'X';

        assertEquals(Game.EMPTY, game.board()[0][0]);
        assertEquals(9, game.emptyCells().size());
    }
}
