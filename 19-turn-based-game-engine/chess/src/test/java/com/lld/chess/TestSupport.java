package com.lld.chess;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Small helpers shared by the tests. */
final class TestSupport {

    /** Not instantiable; this class only holds static helpers. */
    private TestSupport() {
    }

    /** All legal moves of a position, as a set of UCI strings. */
    static Set<String> legalUci(Position p) {
        return p.legalMoves().stream().map(Move::uci).collect(Collectors.toSet());
    }

    /** Plays each move and fails the test on the first one that isn't applied. */
    static Game play(Game game, String... uci) {
        for (String m : uci) {
            assertInstanceOf(MoveResult.Applied.class, game.play(m), m);
        }
        return game;
    }

    /** Same as the varargs version, for a list of moves. */
    static Game play(Game game, List<String> uci) {
        return play(game, uci.toArray(String[]::new));
    }

    /** The castling part of the position's FEN, such as "KQkq" or "-". */
    static String castlingField(Position p) {
        return p.toFen().split(" ")[2];
    }
}
