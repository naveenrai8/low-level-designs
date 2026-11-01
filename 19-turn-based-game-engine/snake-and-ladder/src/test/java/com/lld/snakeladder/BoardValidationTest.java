package com.lld.snakeladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** One test per board rule. Each bad board fails at build time with a message that names the problem. */
class BoardValidationTest {

    /** Supplies bad boards and the error message each one should give. */
    static Stream<Arguments> invalidBoards() {
        return Stream.of(
                bad("board too small", "lastSquare must be >= 2", () -> Board.builder(1).build()),
                bad("jump starts off the board", "leaves the board", () -> Board.builder(10).ladder(0, 5).build()),
                bad("jump ends off the board", "leaves the board", () -> Board.builder(10).ladder(5, 11).build()),
                bad("ladder going down", "a ladder must go up", () -> Board.builder(10).ladder(7, 3).build()),
                bad("ladder to itself", "a ladder must go up", () -> Board.builder(10).ladder(4, 4).build()),
                bad("snake going up", "a snake must go down", () -> Board.builder(10).snake(3, 7).build()),
                bad("jump from the last square", "no jump may start on the last square",
                        () -> Board.builder(10).snake(10, 2).build()),
                bad("two jumps share a start", "two jumps start on square 5",
                        () -> Board.builder(10).ladder(5, 8).snake(5, 2).build()),
                bad("chain: ladder ends on a snake head", "chained jumps are not allowed",
                        () -> Board.builder(10).ladder(2, 7).snake(7, 3).build()),
                bad("chain that would cycle forever", "chained jumps are not allowed",
                        () -> Board.builder(10).ladder(3, 6).snake(6, 3).build()));
    }

    /** Test helper: pairs a name and message with a board builder, so a failing case is easy to read. */
    private static Arguments bad(String name, String message, Supplier<Board> board) {
        return Arguments.of(name, message, board);
    }

    /** Proves each bad board is refused with the exact message. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBoards")
    void rejectsInvalidBoards(String name, String expectedMessage, Supplier<Board> board) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, board::get);
        assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
    }

    /** Proves a ladder from square 1 is allowed, because tokens start on square 0. */
    @Test
    void ladderFromSquareOneIsLegalBecauseTokensStartOffTheBoard() {
        Board board = Board.builder(100).ladder(1, 38).build();

        assertEquals(38, board.destination(1));
        assertEquals(0, board.destination(0), "square 0 is the off-board start");
    }

    /** Proves a ladder may end on the last square. */
    @Test
    void ladderMayEndOnTheLastSquare() {
        assertEquals(100, Board.builder(100).ladder(80, 100).build().destination(80));
    }

    /** Proves that asking for the destination of a square outside the board throws. */
    @Test
    void destinationRejectsSquaresOffTheBoard() {
        Board board = Board.builder(10).build();

        assertThrows(IllegalArgumentException.class, () -> board.destination(-1));
        assertThrows(IllegalArgumentException.class, () -> board.destination(11));
    }

    /** Proves jumps are listed in order of start square, however they were added. */
    @Test
    void jumpsAreReportedInStartOrder() {
        Board board = Board.builder(20).snake(17, 5).ladder(3, 11).build();

        assertEquals(List.of(new Jump(3, 11), new Jump(17, 5)), board.jumps());
    }

    /** Proves the sourced board has 9 ladders and 10 snakes. */
    @Test
    void sourcedBoardHasNineLaddersAndTenSnakes() {
        List<Jump> jumps = Boards.dataGenetics().jumps();

        assertEquals(9, jumps.stream().filter(Jump::isLadder).count());
        assertEquals(10, jumps.stream().filter(j -> !j.isLadder()).count());
    }
}
