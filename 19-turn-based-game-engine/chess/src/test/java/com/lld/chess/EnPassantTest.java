package com.lld.chess;

import static com.lld.chess.TestSupport.legalUci;
import static com.lld.chess.TestSupport.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** En passant: a pawn captures a pawn that just passed it with a double push. */
class EnPassantTest {

    /** Proves en passant removes the pawn beside the capturer, not the pawn on the target square, and resets the clock. */
    @Test
    void capturesThePawnBesideNotTheTargetSquare() {
        Game g = play(Game.standard(), "e2e4", "a7a6", "e4e5", "d7d5");
        assertTrue(g.position().toFen().contains(" d6 "));
        play(g, "e5d6");
        Position p = g.position();
        assertEquals(Optional.empty(), p.pieceAt("d5"));
        assertEquals(Optional.of(Piece.of(Color.WHITE, PieceType.PAWN)), p.pieceAt("d6"));
        assertEquals(0, p.halfmoveClock());
    }

    /** Proves the en-passant chance disappears if it is not used on the very next move. */
    @Test
    void onlyOnTheVeryNextPly() {
        Game g = play(Game.standard(), "e2e4", "a7a6", "e4e5", "d7d5", "g1f3", "a6a5");
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.ILLEGAL), g.play("e5d6"));
    }

    /** Proves en passant is illegal when it would expose the king along the rank, with no special pin code needed. */
    @Test
    void horizontalPinForbidsEnPassant() {
        // Capturing would remove BOTH pawns from rank 5 and expose the king on a5 to the rook on h5. No pin rule is coded: the generic "is my king attacked after the move?" filter rejects it.
        Position p = Position.fromFen("8/8/8/KPp4r/8/8/8/7k w - c6 0 1");
        assertTrue(MoveGenerator.pseudoLegal(p).stream().anyMatch(m -> m.uci().equals("b5c6")));
        assertFalse(legalUci(p).contains("b5c6"));
        assertTrue(legalUci(p).contains("b5b6"));
    }

    /** Proves en passant is a legal answer to check when the pawn that just moved gives the check. */
    @Test
    void enPassantCanCaptureTheCheckingPawn() {
        Game g = play(Game.fromFen("7k/3p4/8/4P3/2K5/8/8/8 b - - 0 1"), "d7d5");
        assertTrue(g.position().inCheck());
        assertTrue(legalUci(g.position()).contains("e5d6"));
        play(g, "e5d6");
        assertFalse(g.position().pieceAt("d5").isPresent());
    }
}
