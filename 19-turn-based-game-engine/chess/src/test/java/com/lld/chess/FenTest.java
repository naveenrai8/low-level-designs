package com.lld.chess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** FEN reading and writing, including the checks that reject impossible positions. */
class FenTest {

    /** Proves parsing a FEN and writing it back gives the same text. */
    @ParameterizedTest
    @ValueSource(strings = {
    /** Proves parsing a FEN and writing it back gives the same text. */
            PerftPositions.START, PerftPositions.POSITION_3, PerftPositions.POSITION_4,
            PerftPositions.POSITION_4_MIRRORED, PerftPositions.POSITION_5, PerftPositions.POSITION_6,
            "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"})
    void roundTrips(String fen) {
        assertEquals(fen, Position.fromFen(fen).toFen());
    }

    /** Proves a four-field FEN gets halfmove 0 and fullmove 1. */
    @Test
    void fourFieldFenDefaultsCountersToZeroAndOne() {
        assertEquals(PerftPositions.KIWIPETE + " 0 1", Position.fromFen(PerftPositions.KIWIPETE).toFen());
    }

    /** Proves each kind of bad FEN is rejected with a message that names the problem. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            no kings                       | 8/8/8/8/8/8/8/8 w - - 0 1                    | no WHITE king
            two white kings                | 4k3/8/8/8/8/8/8/K3K3 w - - 0 1               | more than one WHITE king
            pawn on rank 8                 | P3k3/8/8/8/8/8/8/4K3 w - - 0 1               | pawn on rank 8
            pawn on rank 1                 | 4k3/8/8/8/8/8/8/p3K3 w - - 0 1               | pawn on rank 1
            7 ranks                        | 4k3/8/8/8/8/8/4K3 w - - 0 1                  | needs 8 ranks
            9 files                        | 4k4/8/8/8/8/8/8/4K3 w - - 0 1                | 9 files
            adjacent digit runs            | 4k3/8/8/8/8/8/44/4K3 w - - 0 1               | adjacent digits
            piece past the h-file          | 4k3p/8/8/8/8/8/8/4K3 w - - 0 1               | more than 8 files
            bad piece letter               | 4k3/8/8/8/8/8/8/4K2x w - - 0 1               | not a piece letter
            bad side                       | 4k3/8/8/8/8/8/8/4K3 x - - 0 1                | side to move
            bad castling letter            | 4k3/8/8/8/8/8/8/4K3 w KX - 0 1               | bad castling field
            duplicate castling letter      | 4k3/8/8/8/8/8/8/4K3 w KK - 0 1               | duplicate
            K without a rook on h1         | 4k3/8/8/8/8/8/8/4K3 w K - 0 1                | castling right K
            KQ with the king on d1         | 4k3/8/8/8/8/8/8/R2K3R w KQ - 0 1             | castling right K
            q with the king on d8          | r2k4/8/8/8/8/8/8/4K3 w q - 0 1               | castling right q
            e.p. not a square              | 4k3/8/8/8/8/8/8/4K3 w - z9 0 1               | bad en-passant
            e.p. on the wrong rank         | 4k3/8/8/8/4p3/8/8/4K3 w - e3 0 1             | wrong rank
            e.p. with no pushed pawn       | 4k3/8/8/8/8/8/8/4K3 w - e6 0 1               | double pawn push
            e.p. pawn's start occupied     | 4k3/4p3/8/4p3/8/8/8/4K3 w - e6 0 1           | double pawn push
            e.p. square occupied           | 4k3/8/4n3/4p3/8/8/8/4K3 w - e6 0 1           | double pawn push
            e.p. with halfmove 1           | 4k3/8/8/8/4P3/8/8/4K3 b - e3 1 1             | resets the halfmove clock
            side not to move is in check   | 4k3/8/8/8/8/8/8/4K2r b - - 0 1               | not to move is in check
            negative halfmove              | 4k3/8/8/8/8/8/8/4K3 w - - -1 1               | halfmove clock must be
            fullmove zero                  | 4k3/8/8/8/8/8/8/4K3 w - - 0 0                | fullmove number must be
            non-numeric counter            | 4k3/8/8/8/8/8/8/4K3 w - - x 1                | not a number
            5 fields                       | 4k3/8/8/8/8/8/8/4K3 w - - 0                  | 4 or 6 fields
            """)
    void rejectsInvalidFen(String why, String fen, String messageFragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Position.fromFen(fen));
        assertTrue(e.getMessage().contains(messageFragment), why + ": " + e.getMessage());
    }

    /** Proves the FEN records the en-passant square after every double push, even if nobody can capture. */
    @Test
    void enPassantSquareIsRecordedAfterEveryDoublePush() {
        // PGN §16.1.3.4: recorded even when no pawn can capture it (repetition handles that, see GameEndTest)
        Game g = TestSupport.play(Game.standard(), "e2e4");
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", g.position().toFen());
    }
}
