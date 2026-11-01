package com.lld.chess;

import static com.lld.chess.TestSupport.castlingField;
import static com.lld.chess.TestSupport.legalUci;
import static com.lld.chess.TestSupport.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Castling rules: the squares the king crosses, the rook path, and when the right is lost. */
class CastlingTest {

    private static final String BOTH_SIDES = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1";

    /** Proves castling moves the rook as well as the king, for both colours. */
    @Test
    void castlingMovesTheRookToo() {
        Game g = play(Game.fromFen(BOTH_SIDES), "e1g1");
        assertEquals("r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1", g.position().toFen());
        play(g, "e8c8");
        assertEquals("2kr3r/8/8/8/8/8/8/R4RK1 w - - 2 2", g.position().toFen());
    }

    /** Proves a king in check cannot castle on either side. */
    @Test
    void notOutOfCheck() {
        Set<String> moves = legalUci(Position.fromFen("4k3/8/8/8/4r3/8/8/R3K2R w KQ - 0 1"));
        assertFalse(moves.contains("e1g1"));
        assertFalse(moves.contains("e1c1"));
    }

    /** Proves a king cannot castle across an attacked square (f1), while the other side stays legal. */
    @Test
    void notThroughCheck() {
        Set<String> moves = legalUci(Position.fromFen("4k3/8/8/8/5r2/8/8/R3K2R w KQ - 0 1")); // f1 attacked
        assertFalse(moves.contains("e1g1"));
        assertTrue(moves.contains("e1c1"));
    }

    /** Proves a king cannot castle onto an attacked square (g1), while the other side stays legal. */
    @Test
    void notIntoCheck() {
        Set<String> moves = legalUci(Position.fromFen("4k3/8/8/8/6r1/8/8/R3K2R w KQ - 0 1")); // g1 attacked
        assertFalse(moves.contains("e1g1"));
        assertTrue(moves.contains("e1c1"));
    }

    /** Proves queen-side castling is still legal when b1 is attacked, because only the rook crosses b1. */
    @Test
    void queensideAllowsAttackedB1() {
        // The king never crosses b1; only the rook does. b1 must be empty, not safe.
        Position p = Position.fromFen("4k3/8/8/8/1r6/8/8/R3K2R w KQ - 0 1");
        assertTrue(p.isAttacked("b1", Color.BLACK));
        assertTrue(legalUci(p).contains("e1c1"));
    }

    /** Proves a piece on b1 blocks queen-side castling, even though the king never crosses that square. */
    @Test
    void queensideNeedsB1Empty() {
        Set<String> moves = legalUci(Position.fromFen("4k3/8/8/8/8/8/8/RN2K2R w KQ - 0 1"));
        assertFalse(moves.contains("e1c1"));
        assertTrue(moves.contains("e1g1"));
    }

    /** Proves moving the king loses both rights for good, even if the king walks back to e1. */
    @Test
    void kingMoveLosesBothRightsForGood() {
        Game g = play(Game.fromFen(BOTH_SIDES), "e1f1", "h8h7", "f1e1");
        assertEquals("q", castlingField(g.position()));
        assertFalse(legalUci(play(g, "a8b8").position()).contains("e1g1")); // king back on e1: still no right
    }

    /** Proves moving the h1 rook loses only the king-side right, and nothing else. */
    @Test
    void ownRookMoveLosesThatSideOnly() {
        assertEquals("Qkq", castlingField(play(Game.fromFen(BOTH_SIDES), "h1h2").position()));
    }

    /** Proves capturing a rook on its home square removes that right, though the rook never moved. */
    @Test
    void rookCapturedOnItsHomeSquareLosesTheRight() {
        // The case a "did this rook move?" flag misses: the rook never moved, it was taken.
        Game g = play(Game.fromFen("4k3/8/8/8/8/8/6b1/R3K2R b KQ - 0 1"), "g2h1");
        assertEquals("Q", castlingField(g.position()));
    }

    /** Proves castling is entered as the king moving two squares (e1g1); "king takes own rook" (e1h1) is rejected. */
    @Test
    void castlingIsPlayedAsKingTwoSquaresNotKingOntoRook() {
        Game g = Game.fromFen(BOTH_SIDES);
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.ILLEGAL), g.play("e1h1"));
    }
}
