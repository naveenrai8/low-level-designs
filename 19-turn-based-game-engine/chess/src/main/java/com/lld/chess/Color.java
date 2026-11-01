package com.lld.chess;

/** A side. {@link #forward()} is the rank delta of that side's pawn push. */
public enum Color {
    /** White moves up the board, towards rank 8. */
    WHITE(1, 'w'),
    /** Black moves down the board, towards rank 1. */
    BLACK(-1, 'b');

    private final int forward;
    private final char fen;

    /** Stores the pawn direction (+1 up the board, -1 down) and the FEN letter for this side. */
    Color(int forward, char fen) {
        this.forward = forward;
        this.fen = fen;
    }

    /** The other side. */
    public Color opposite() {
        return this == WHITE ? BLACK : WHITE;
    }

    /** Which way this side's pawns walk: +1 for white, -1 for black. */
    int forward() {
        return forward;
    }

    /** The side-to-move letter used in FEN: 'w' or 'b'. */
    char fen() {
        return fen;
    }
}
