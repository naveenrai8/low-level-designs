package com.lld.chess;

import static com.lld.chess.TestSupport.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Promotion: one move per piece, the UCI suffix, and capture-promotions. */
class PromotionTest {

    private static final String A7_PAWN = "8/P7/8/8/8/8/8/k6K w - - 0 1";

    /** Proves a pawn on the 7th rank has four promotion moves. */
    @Test
    void oneMovePerPromotionPiece() {
        assertEquals(4, Position.fromFen(A7_PAWN).legalMoves().stream().filter(m -> m.promotion() != null).count());
    }

    /** Proves the suffix picks the piece the pawn becomes. */
    @ParameterizedTest
    @CsvSource({"a7a8q, QUEEN", "a7a8r, ROOK", "a7a8b, BISHOP", "a7a8n, KNIGHT"})
    void promotesToTheNamedPiece(String uci, PieceType expected) {
        Game g = play(Game.fromFen(A7_PAWN), uci);
        assertEquals(Optional.of(Piece.of(Color.WHITE, expected)), g.position().pieceAt("a8"));
        assertEquals(Optional.empty(), g.position().pieceAt("a7"));
    }

    /** Proves a promotion with no piece letter is rejected with its own reason and changes nothing. */
    @Test
    void missingSuffixIsItsOwnRejection() {
        Game g = Game.fromFen(A7_PAWN);
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.PROMOTION_PIECE_REQUIRED), g.play("a7a8"));
        assertEquals(A7_PAWN, g.position().toFen()); // rejected moves change nothing
    }

    /** Proves capturing while promoting gives four more moves, and resets the halfmove clock. */
    @Test
    void capturePromotionsAreGeneratedToo() {
        Position p = Position.fromFen("1r6/P7/8/8/8/8/8/k6K w - - 0 1");
        assertEquals(8, p.legalMoves().stream().filter(m -> m.promotion() != null).count());
        Game g = play(Game.fromFen("1r6/P7/8/8/8/8/8/k6K w - - 7 40"), "a7b8n");
        assertEquals(0, g.position().halfmoveClock());
    }

    /** Proves promoting to a knight can be checkmate when a queen would not be. */
    @Test
    void underPromotionCanBeTheOnlyMate() {
        String fen = "6bn/5Ppk/7p/8/8/8/8/K7 w - - 0 1";
        Game knight = play(Game.fromFen(fen), "f7f8n");
        assertEquals(Status.CHECKMATE, knight.status());
        assertEquals(Optional.of(Color.WHITE), knight.winner());
        assertNotEquals(Status.CHECKMATE, play(Game.fromFen(fen), "f7f8q").status());
    }
}
