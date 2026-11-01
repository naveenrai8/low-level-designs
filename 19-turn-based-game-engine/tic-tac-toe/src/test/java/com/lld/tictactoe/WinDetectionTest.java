package com.lld.tictactoe;

import static com.lld.tictactoe.Games.cells;
import static com.lld.tictactoe.Games.config;
import static com.lld.tictactoe.Games.playAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Draw;
import com.lld.tictactoe.MoveResult.Won;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests that a win is found for every line shape, board size, win length and player count. */
class WinDetectionTest {

    /** Supplies one winning game for each line shape: row, column, diagonal and anti-diagonal. */
    static Stream<Arguments> classicWins() {
        return Stream.of(
                Arguments.of("row", new String[] {"1,0", "0,0", "1,1", "0,1", "1,2"}, "X", cells(1, 0, 1, 1, 1, 2)),
                Arguments.of("column", new String[] {"0,2", "0,0", "1,2", "1,1", "2,2"}, "X", cells(0, 2, 1, 2, 2, 2)),
                Arguments.of("diagonal", new String[] {"0,0", "0,1", "1,1", "0,2", "2,2"}, "X", cells(0, 0, 1, 1, 2, 2)),
                Arguments.of("anti-diagonal", new String[] {"0,0", "0,2", "0,1", "1,1", "2,2", "2,0"}, "O", cells(0, 2, 1, 1, 2, 0)));
    }

    /** Proves each line shape wins and reports the exact winning cells. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("classicWins")
    void detectsEveryLineShapeOnTheClassicBoard(String shape, String[] moves, String winner, List<Cell> line) {
        Game game = new Game(GameConfig.classic());

        MoveResult result = playAll(game, moves);

        Won won = assertInstanceOf(Won.class, result);
        assertEquals(winner, won.winner().id());
        assertEquals(line, won.line());
        assertEquals(Status.WON, game.status());
        assertEquals(winner, game.winner().orElseThrow().id());
        assertEquals(List.of(), game.emptyCells().stream().filter(line::contains).toList());
    }

    /** Proves that a full board with no line is a draw. */
    @Test
    void fullBoardWithoutALineIsADraw() {
        Game game = new Game(GameConfig.classic());
        // X O X / X O O / O X X
        MoveResult last = playAll(game, "0,0", "0,1", "0,2", "1,1", "1,0", "1,2", "2,1", "2,0", "2,2");

        assertInstanceOf(Draw.class, last);
        assertEquals(Status.DRAW, game.status());
        assertEquals(java.util.Optional.empty(), game.winner());
    }

    /** Proves that on a 6x6 board with K=4, four in a row on a diagonal that is not the main one still wins. */
    @Test
    void fourInARowOnSixBySixOffTheMainDiagonal() {
        Game game = new Game(config(6, 4, 2));
        // X plays (1,2) (2,3) (3,4) (4,5); O plays along row 5.
        MoveResult last = playAll(game, "1,2", "5,0", "2,3", "5,1", "3,4", "5,2", "4,5");

        Won won = assertInstanceOf(Won.class, last);
        assertEquals(cells(1, 2, 2, 3, 3, 4, 4, 5), won.line());
    }

    /** Proves that three in a row is not a win when K is 4. */
    @Test
    void threeOfFourInARowIsNotAWinWhenKIsFour() {
        Game game = new Game(config(6, 4, 2));

        assertInstanceOf(Accepted.class, playAll(game, "0,0", "5,5", "0,1", "5,4", "0,2"));
    }

    /** Proves that the win check works with three players on a 5x5 board with K=3. */
    @Test
    void threePlayersOnFiveByFiveWithKThree() {
        Game game = new Game(config(5, 3, 3)); // X, O, A in turn
        MoveResult last = playAll(game,
                "0,0", "1,0", "2,0", // X O A
                "0,1", "1,1", "2,1", // X O A
                "4,4", "4,3", "2,2"); // X O A: A completes row 2

        Won won = assertInstanceOf(Won.class, last);
        assertEquals("A", won.winner().id());
        assertEquals(cells(2, 0, 2, 1, 2, 2), won.line());
    }

    /**
     * The ±1 trick gives each player a sign. Three players can't have three distinct signs, so two of them share one, and a row like X·A·X then sums to 3 and reads as an X win. Each script below ends with row 0 holding two stones of one player and one of another, covering all three possible sharing pairs. So the test fails under any sign mapping.
     */
    static Stream<Arguments> rowsWithARepeatedOwner() {
        return Stream.of( // X, O, A move in turn on 3x3, K = 3
                Arguments.of("X A X", new String[] {"0,0", "1,0", "0,1", "0,2"}),
                Arguments.of("O A O", new String[] {"2,2", "0,0", "0,1", "2,0", "0,2"}),
                Arguments.of("X O X", new String[] {"0,0", "0,1", "1,0", "0,2"}));
    }

    /** Proves that the per-player counters handle three players on a row where the old +1/-1 trick wrongly sees a win. */
    @ParameterizedTest(name = "row 0 = {0}")
    @MethodSource("rowsWithARepeatedOwner")
    void lineCountersWorkForThreePlayersWhereThePlusMinusOneTrickFails(String row, String[] moves) {
        Game game = new Game(config(3, 3, 3));

        assertInstanceOf(Accepted.class, playAll(game, moves));
        assertEquals(Status.IN_PROGRESS, game.status());
    }

    /** Proves that with K=1 the first move wins. */
    @Test
    void winLengthOneMeansTheFirstMoveWins() {
        Game game = new Game(config(3, 1, 2));

        Won won = assertInstanceOf(Won.class, playAll(game, "2,1"));
        assertEquals(cells(2, 1), won.line());
    }
}
