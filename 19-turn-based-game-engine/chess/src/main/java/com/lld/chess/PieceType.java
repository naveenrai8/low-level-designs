package com.lld.chess;

/** The six piece kinds, as plain data. How each one moves lives in {@link MoveGenerator}'s direction tables, not in per-piece classes. Legality depends on the whole position (pins, check, castling rights, the en-passant square), and a promotion changes a piece's type, so "the piece knows its moves" is the wrong model. See the README, "why not sealed piece classes". */
public enum PieceType {
    /** Moves forward, captures diagonally, and promotes on the last rank. */
    PAWN('p'),
    /** Jumps in an L shape and is the only piece that can jump over others. */
    KNIGHT('n'),
    /** Slides diagonally, so it stays on one square colour. */
    BISHOP('b'),
    /** Slides along ranks and files. */
    ROOK('r'),
    /** Slides like a rook or a bishop. */
    QUEEN('q'),
    /** Steps one square; the game is lost if it cannot escape attack. */
    KING('k');

    private final char fen;

    /** Remembers the lower-case letter used for this kind in FEN and UCI. */
    PieceType(char fen) {
        this.fen = fen;
    }

    /** Lower-case letter, as used in FEN for black and in UCI promotion suffixes. */
    char fen() {
        return fen;
    }

    /** Turns a FEN letter into a piece kind. Case is ignored, because colour is handled elsewhere. Throws {@code IllegalArgumentException} for any other character. */
    static PieceType fromFen(char c) {
        for (PieceType t : values()) {
            if (t.fen == Character.toLowerCase(c)) {
                return t;
            }
        }
        throw new IllegalArgumentException("not a piece letter: '" + c + "'");
    }
}
