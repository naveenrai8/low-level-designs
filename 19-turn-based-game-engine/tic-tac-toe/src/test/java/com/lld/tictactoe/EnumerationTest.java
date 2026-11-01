package com.lld.tictactoe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Draw;
import com.lld.tictactoe.MoveResult.Won;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Walks every possible 3×3 game with the module's own {@link Game} (play + undo), stopping each game at its first win. Two totals are sourced, so this is an end-to-end check of the rules, the win detector and undo together:
 *
 * <ul>
 *   <li>255,168 possible games; 5,478 legal positions (empty board included). Source: https://en.wikipedia.org/wiki/Game_complexity (tic-tac-toe section).
 *   <li>The X / O / draw split and the games-by-length counts are <b>computed by this test</b>, not sourced. They are asserted so a regression shows up, not because an outside figure confirms them.
 * </ul>
 *
 * <p>Forgetting to stop at the first win gives 9! = 362,880 "games".
 */
class EnumerationTest {

    private long games;
    private long xWins;
    private long oWins;
    private long draws;
    private final long[] gamesByLength = new long[10];
    private final Set<String> positions = new HashSet<>();

    /** Proves that playing every legal classic game gives the known totals: all games, wins by X, wins by O and draws, with no illegal position ever reached. */
    @Test
    void enumeratesEveryClassicGame() {
        Game game = new Game(GameConfig.classic());
        positions.add(game.render());

        explore(game);

        assertEquals(255_168, games);     // sourced
        assertEquals(5_478, positions.size()); // sourced
        assertEquals(131_184, xWins);     // computed
        assertEquals(77_904, oWins);      // computed
        assertEquals(46_080, draws);      // computed
        assertEquals(games, xWins + oWins + draws);
        assertArrayEquals(new long[] {0, 0, 0, 0, 0, 1_440, 5_328, 47_952, 72_576, 127_872}, gamesByLength); // computed
        System.out.printf("games=%d X=%d O=%d draws=%d positions=%d byLength=%s%n",
                games, xWins, oWins, draws, positions.size(), java.util.Arrays.toString(gamesByLength));
    }

    /** Walks every legal continuation from this position, using play and undo, and counts each finished game. */
    private void explore(Game game) {
        for (Cell cell : game.emptyCells()) {
            String mover = game.playerToMove().orElseThrow().id();
            MoveResult result = game.play(mover, cell.row(), cell.col());
            positions.add(game.render());
            switch (result) {
                case Won w -> finished(game, w.winner().id().equals("X") ? 'X' : 'O');
                case Draw d -> finished(game, '-');
                case Accepted a -> explore(game);
                case MoveResult.Rejected r -> throw new AssertionError("enumeration made an illegal move: " + r);
            }
            game.undo();
        }
    }

    /** Counts one finished game under its outcome (X win, O win or draw). */
    private void finished(Game game, char outcome) {
        games++;
        gamesByLength[game.history().size()]++;
        switch (outcome) {
            case 'X' -> xWins++;
            case 'O' -> oWins++;
            default -> draws++;
        }
    }
}
