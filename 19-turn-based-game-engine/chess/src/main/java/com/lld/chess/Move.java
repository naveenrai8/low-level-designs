package com.lld.chess;

import java.util.Objects;

/**
 * One move, as produced by the move generator. Callers never construct moves to play them: {@link Game#play(String)} parses UCI text and <b>matches it against the legal-move list</b>, so a parsed move is never trusted.
 *
 * @param from the origin square index (a1 = 0 ... h8 = 63)
 * @param to the destination square; for castling, the king's destination (e1g1, not e1h1)
 * @param kind what kind of move this is; a promotion is a {@code QUIET} or {@code CAPTURE} with a non-null {@code promotion}
 * @param promotion the piece promoted to, or {@code null}
 */
public record Move(int from, int to, Kind kind, PieceType promotion) {

    /** The special handling a move needs when it is applied: what moves, what is removed, what is remembered. */
    public enum Kind { QUIET, CAPTURE, DOUBLE_PUSH, EN_PASSANT, CASTLE_KINGSIDE, CASTLE_QUEENSIDE }

    /** Checks the squares are on the board and different, the kind is set, and the promotion piece is allowed. Throws {@code IllegalArgumentException} otherwise. */
    public Move {
        if (from < 0 || from > 63 || to < 0 || to > 63 || from == to) {
            throw new IllegalArgumentException("bad squares: " + from + " -> " + to);
        }
        Objects.requireNonNull(kind, "kind");
        if (promotion == PieceType.PAWN || promotion == PieceType.KING) {
            throw new IllegalArgumentException("cannot promote to " + promotion);
        }
    }

    /** Builds a plain move with no promotion. */
    static Move of(int from, int to, Kind kind) {
        return new Move(from, to, kind, null);
    }

    /** True if the move takes a piece. En passant counts, even though the target square is empty. */
    public boolean isCapture() {
        return kind == Kind.CAPTURE || kind == Kind.EN_PASSANT;
    }

    /** True for either castling move. */
    public boolean isCastle() {
        return kind == Kind.CASTLE_KINGSIDE || kind == Kind.CASTLE_QUEENSIDE;
    }

    /** UCI long algebraic: {@code e2e4}, {@code e7e8q}, {@code e1g1}. */
    public String uci() {
        String s = Square.name(from) + Square.name(to);
        return promotion == null ? s : s + promotion.fen();
    }

    /** Prints the move as UCI text, which keeps test failures and logs readable. */
    @Override
    public String toString() {
        return uci();
    }
}
