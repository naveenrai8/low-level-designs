package com.lld.chess;

import static com.lld.chess.TestSupport.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The public Game API: how input is accepted or rejected, and what undo does. */
class GameApiTest {

    /** Proves text that is not UCI (or null) is rejected as MALFORMED. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "e2e", "e9e4", "E2E4", "e2e4k", "e2-e4", " e2e4", "e2e4qq"})
    void malformed(String uci) {
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.MALFORMED), Game.standard().play(uci));
    }

    /** Proves well-formed but illegal moves are rejected as ILLEGAL and leave the game unchanged. */
    @ParameterizedTest
    @ValueSource(strings = {"e2e5", "e2e4q", "e1e2", "e7e5", "a1a3", "e3e4"})
    void illegal(String uci) {
        Game g = Game.standard();
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.ILLEGAL), g.play(uci));
        assertEquals(Position.START_FEN, g.position().toFen());
        assertTrue(g.moveLog().isEmpty());
    }

    /** Proves a pinned piece cannot move, using the plain legal-move filter. */
    @Test
    void pinnedPieceCannotMove() {
        // the e2 knight shields the e1 king from the e8 rook
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.ILLEGAL),
                Game.fromFen("4r2k/8/8/8/8/8/4N3/4K3 w - - 0 1").play("e2c3"));
    }

    /** Proves a successful move returns the move played and the new status. */
    @Test
    void appliedCarriesTheMoveAndStatus() {
        MoveResult r = Game.standard().play("e2e4");
        MoveResult.Applied a = assertInstanceOf(MoveResult.Applied.class, r);
        assertEquals("e2e4", a.move().uci());
        assertEquals(Move.Kind.DOUBLE_PUSH, a.move().kind());
        assertEquals(Status.IN_PROGRESS, a.status());
    }

    /** Proves the move log, the halfmove clock and the fullmove number update correctly. */
    @Test
    void moveLogAndCounters() {
        Game g = play(Game.standard(), "e2e4", "e7e5", "g1f3");
        assertEquals(List.of("e2e4", "e7e5", "g1f3"), g.moveLog());
        assertEquals(1, g.position().halfmoveClock());
        assertEquals(2, g.position().fullmoveNumber()); // increments after Black's move only
    }

    /** Proves undo on a fresh game does nothing and returns false. */
    @Test
    void undoNothingIsFalse() {
        assertFalse(Game.standard().undo());
    }

    /** Proves undoing a checkmating move puts the game back in progress. */
    @Test
    void undoAfterMateReopensTheGame() {
        Game g = play(Game.standard(), "f2f3", "e7e5", "g2g4", "d8h4");
        assertTrue(g.undo());
        assertEquals(Status.IN_PROGRESS, g.status());
        assertEquals(List.of("f2f3", "e7e5", "g2g4"), g.moveLog());
        play(g, "d8h4");
        assertEquals(Status.CHECKMATE, g.status());
    }

    /** Proves a game that starts from a stalemate FEN is already over and rejects moves. */
    @Test
    void gameFromATerminalFenIsAlreadyOver() {
        Game g = Game.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        assertEquals(Status.STALEMATE, g.status());
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.GAME_OVER), g.play("h8g8"));
    }

    /** Proves the square-name accessors throw for names that are not squares. */
    @Test
    void positionAccessorsValidateSquares() {
        Position p = Position.start();
        assertEquals(Piece.of(Color.WHITE, PieceType.KING), p.pieceAt("e1").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> p.pieceAt("i9"));
        assertThrows(IllegalArgumentException.class, () -> p.isAttacked("e0", Color.WHITE));
    }

    /** Proves a Move cannot be built with equal squares or a king promotion. */
    @Test
    void moveRejectsNonsenseConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Move(12, 12, Move.Kind.QUIET, null));
        assertThrows(IllegalArgumentException.class, () -> new Move(52, 60, Move.Kind.QUIET, PieceType.KING));
    }
}
