package com.lld.chess;

import static com.lld.chess.TestSupport.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** How games end: checkmate, stalemate, dead positions, the 50 and 75 move rules, and repetition. */
class GameEndTest {

    private static final List<String> KNIGHT_SHUFFLE = List.of("g1f3", "g8f6", "f3g1", "f6g8");

    /** Proves the shortest mate ends the game, names the winner, and blocks further moves and claims. */
    @Test
    void foolsMate() {
        Game g = play(Game.standard(), "f2f3", "e7e5", "g2g4", "d8h4");
        assertEquals(Status.CHECKMATE, g.status());
        assertEquals(Optional.of(Color.BLACK), g.winner());
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.GAME_OVER), g.play("a2a3"));
        assertEquals(new ClaimResult.Rejected(ClaimResult.Reason.GAME_OVER), g.claimDraw());
    }

    /** Proves no legal move while not in check is a draw with no winner. */
    @Test
    void stalemate() {
        Game g = play(Game.fromFen("7k/8/6K1/8/8/8/5Q2/8 w - - 0 1"), "f2f7");
        assertEquals(Status.STALEMATE, g.status());
        assertEquals(Optional.empty(), g.winner());
        assertTrue(g.status().isDraw());
    }

    /** Proves which material combinations count as a dead position. */
    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            K v K                       | 8/8/8/8/8/8/8/K6k w - - 0 1      | true
            K+B v K                     | 8/8/8/8/8/8/8/KB5k w - - 0 1     | true
            K+N v K                     | 8/8/8/8/8/8/8/KN5k w - - 0 1     | true
            K+B v K+B same colour       | 5b2/8/8/8/8/8/8/K1B4k w - - 0 1  | true
            K+B+B v K same colour       | 8/8/8/8/8/8/8/K1B1B2k w - - 0 1  | true
            K+B v K+B opposite colour   | 5b2/8/8/8/8/8/8/K4B1k w - - 0 1  | false
            K+N+N v K                   | 8/8/8/8/8/8/8/KNN4k w - - 0 1    | false
            K+N v K+N                   | 6n1/8/8/8/8/8/8/KN5k w - - 0 1   | false
            K+P v K                     | 8/8/8/8/8/8/P7/K6k w - - 0 1     | false
            K+R v K                     | 8/8/8/8/8/8/R7/K6k w - - 0 1     | false
            K+Q v K                     | 8/8/8/8/8/8/Q7/K6k w - - 0 1     | false
            """)
    void insufficientMaterial(String name, String fen, boolean dead) {
        assertEquals(dead, Position.fromFen(fen).hasInsufficientMaterial(), name);
    }

    /** Proves a capture that leaves only two kings ends the game at once. */
    @Test
    void captureDownToBareKingsEndsTheGame() {
        Game g = play(Game.fromFen("8/8/8/8/8/8/6Rk/K7 b - - 0 1"), "h2g2");
        assertEquals(Status.DRAW_INSUFFICIENT_MATERIAL, g.status());
    }

    /** Proves the 50-move rule only becomes claimable at halfmove 100, and does not end the game by itself. */
    @Test
    void fiftyMoveRuleIsAClaimNotAnEnding() {
        Game g = Game.fromFen("7k/8/8/8/8/8/8/KR6 w - - 99 80");
        assertEquals(Optional.empty(), g.claimableDraw());
        assertEquals(new ClaimResult.Rejected(ClaimResult.Reason.NO_DRAW_TO_CLAIM), g.claimDraw());
        play(g, "b1b2"); // halfmove 100
        assertEquals(Status.IN_PROGRESS, g.status());
        assertEquals(Optional.of(DrawClaim.FIFTY_MOVE_RULE), g.claimableDraw());
        assertEquals(new ClaimResult.Claimed(Status.DRAW_FIFTY_MOVE), g.claimDraw());
        assertEquals(new MoveResult.Rejected(MoveResult.Reason.GAME_OVER), g.play("h8g8"));
        assertEquals(new ClaimResult.Rejected(ClaimResult.Reason.GAME_OVER), g.claimDraw());
    }

    /** Proves undo first withdraws a draw claim and only then takes back a move. */
    @Test
    void undoAfterAClaimWithdrawsTheClaimBeforeAnyMove() {
        // undo takes back the last action, and a claim is an action
        Game g = play(Game.fromFen("7k/8/8/8/8/8/8/KR6 w - - 99 80"), "b1b2");
        g.claimDraw();
        assertTrue(g.undo());
        assertEquals(Status.IN_PROGRESS, g.status());
        assertEquals(List.of("b1b2"), g.moveLog());
        assertEquals(Optional.of(DrawClaim.FIFTY_MOVE_RULE), g.claimableDraw());
        assertTrue(g.undo());
        assertEquals(99, g.position().halfmoveClock());
    }

    /** Proves a claim made before any move can still be withdrawn, and a second undo returns false. */
    @Test
    void claimFromTheStartingFenCanBeWithdrawnWithNoMoveToTakeBack() {
        Game g = Game.fromFen("7k/8/8/8/8/8/8/KR6 w - - 100 80");
        assertEquals(new ClaimResult.Claimed(Status.DRAW_FIFTY_MOVE), g.claimDraw());
        assertTrue(g.undo());
        assertEquals(Status.IN_PROGRESS, g.status());
        assertFalse(g.undo());
        assertEquals(Status.IN_PROGRESS, g.status());
    }

    /** Proves reaching halfmove 150 ends the game with no claim needed. */
    @Test
    void seventyFiveMoveRuleEndsTheGameAutomatically() {
        Game g = play(Game.fromFen("7k/8/8/8/8/8/8/KR6 w - - 149 100"), "b1b2");
        assertEquals(Status.DRAW_SEVENTY_FIVE_MOVE, g.status());
    }

    /** Proves a capture at halfmove 149 resets the clock, so the game goes on. */
    @Test
    void captureAtHalfmove149ResetsTheClock() {
        Game g = play(Game.fromFen("7k/8/8/8/8/8/1p6/KR6 w - - 149 100"), "b1b2");
        assertEquals(0, g.position().halfmoveClock());
        assertEquals(Status.IN_PROGRESS, g.status());
    }

    /** Proves a mating move that reaches halfmove 150 is checkmate, not a draw. */
    @Test
    void mateOnTheSeventyFifthMoveIsStillMate() {
        Game g = play(Game.fromFen("6k1/5ppp/8/8/8/8/8/K3R3 w - - 149 100"), "e1e8");
        assertEquals(150, g.position().halfmoveClock());
        assertEquals(Status.CHECKMATE, g.status()); // FIDE 9.6.2
    }

    /** Proves the third repetition is only claimable and the fifth ends the game automatically. */
    @Test
    void threefoldIsClaimableFivefoldIsAutomatic() {
        Game g = play(Game.standard(), KNIGHT_SHUFFLE);       // start position seen twice
        assertEquals(Optional.empty(), g.claimableDraw());
        play(g, KNIGHT_SHUFFLE);                              // three times
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw());
        assertEquals(Status.IN_PROGRESS, g.status());
        play(g, KNIGHT_SHUFFLE);                              // four
        assertEquals(Status.IN_PROGRESS, g.status());
        play(g, KNIGHT_SHUFFLE);                              // five
        assertEquals(Status.DRAW_FIVEFOLD_REPETITION, g.status());
        assertEquals(16, g.moveLog().size());
    }

    /** Proves undo lowers the repetition count of the position it removes. */
    @Test
    void undoRemovesTheOccurrenceOfTheUndonePosition() {
        // Unsaturated count: the start position has been seen twice. Undo + replay must leave it at 2, so this fails if undo skips the decrement or decrements the wrong position.
        Game g = play(Game.standard(), KNIGHT_SHUFFLE);
        assertTrue(g.undo());
        play(g, "f6g8");
        assertEquals(Optional.empty(), g.claimableDraw());
        play(g, KNIGHT_SHUFFLE); // the real third occurrence
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw());
    }

    /** Proves the same board with a different side to move has a different repetition key. */
    @Test
    void sideToMoveIsPartOfTheRepetitionKey() {
        String board = "7k/8/8/4p3/4P3/8/8/K7";
        assertNotEquals(Position.fromFen(board + " w - -").repetitionKey(),
                Position.fromFen(board + " b - -").repetitionKey());
    }

    /** Proves the same board seen with the other side to move does not count towards a repetition. */
    @Test
    void sameBoardWithTheOtherSideToMoveIsNotARepetition() {
        // White triangulates (3 moves) while Black shuffles (2), so the start board comes back with Black to move at ply 5 and with White to move at ply 12. Seen 3 times, but only twice as the same position (FIDE 9.2.3: "the same player has the move").
        List<String> white = List.of("a1b1", "b1b2", "b2a1");
        List<String> black = List.of("h8g8", "g8h8");
        Game g = Game.fromFen("7k/8/8/4p3/4P3/8/8/K7 w - - 0 1");
        for (int i = 0; i < 6; i++) {
            play(g, white.get(i % 3), black.get(i % 2));
        }
        assertEquals(Optional.empty(), g.claimableDraw());
        for (int i = 6; i < 12; i++) {
            play(g, white.get(i % 3), black.get(i % 2));
        }
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw()); // ply 24: third time, White to move
    }

    /** Proves an en-passant square nobody can use is ignored when comparing positions. */
    @Test
    void uncapturableEnPassantSquareDoesNotBreakRepetition() {
        // After 1.e4 the FEN says "e3" but no black pawn can take: FIDE 9.2.3.1 counts it as the same position as the one reached later with "-".
        Game g = play(Game.standard(), "e2e4");
        assertEquals("e3", g.position().toFen().split(" ")[3]);
        assertEquals("-", g.position().repetitionKey().split(" ")[3]);
        play(g, "g8f6", "g1f3", "f6g8", "f3g1", "g8f6", "g1f3", "f6g8", "f3g1"); // 9 plies: third occurrence
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw());
    }

    /** Proves an en-passant capture that is only possible on paper (it would be a pin) is ignored for repetition. */
    @Test
    void pinnedEnPassantDoesNotCountEither() {
        // After d2-d4 the c4 pawn could take e.p. geometrically, but c4xd3 would open rank 4 to the rook.
        List<String> shuffle = List.of("a4a5", "g1g2", "a5a4", "g2g1");
        Game g = play(Game.fromFen("8/8/8/8/k1p4R/8/3P4/6K1 w - - 0 1"), "d2d4");
        play(play(g, shuffle), shuffle);
        assertEquals(9, g.moveLog().size());
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw());
    }

    /** Control case: with a legal en-passant capture, the position just after the push is different and is not a repetition. */
    @Test
    void legalEnPassantMakesThePositionDifferent() {
        // Control for the test above: no rook, so c4xd3 is legal and the post-push position is unique.
        List<String> shuffle = List.of("a4a5", "g1g2", "a5a4", "g2g1");
        Game g = play(Game.fromFen("8/8/8/8/k1p5/8/3P4/6K1 w - - 0 1"), "d2d4");
        play(play(g, shuffle), shuffle);
        assertEquals(Optional.empty(), g.claimableDraw());
        play(g, shuffle);
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw());
    }

    /** Proves positions with different castling rights are not counted as the same position. */
    @Test
    void castlingRightsArePartOfThePosition() {
        // Same placement, but the first occurrence still had castling rights: not a repetition.
        List<String> rook = List.of("h1g1", "h8g8", "g1h1", "g8h8");
        Game g = play(play(Game.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"), rook), rook);
        // this placement has now been on the board 3 times, but the first one had "KQkq", the others "Qq"
        assertEquals(2, Collections.frequency(g.moveLog(), "h1g1"));
        assertEquals(Optional.empty(), g.claimableDraw());
        play(g, rook);
        assertEquals(Optional.of(DrawClaim.THREEFOLD_REPETITION), g.claimableDraw());
    }
}
