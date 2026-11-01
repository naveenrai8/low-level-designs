package com.lld.chess;

import java.util.Objects;

/** A coloured piece. A value: 12 cached instances, compared by value like any record. */
public record Piece(Color color, PieceType type) {

    private static final Piece[] CACHE = new Piece[Color.values().length * PieceType.values().length];

    static {
        for (Color c : Color.values()) {
            for (PieceType t : PieceType.values()) {
                CACHE[c.ordinal() * PieceType.values().length + t.ordinal()] = new Piece(c, t);
            }
        }
    }

    /** Rejects a null colour or type up front, so a half-built piece can never exist. */
    public Piece {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(type, "type");
    }

    /** Returns the shared instance for this colour and kind, so no new piece objects are made during search. */
    public static Piece of(Color color, PieceType type) {
        return CACHE[color.ordinal() * PieceType.values().length + type.ordinal()];
    }

    /** FEN letter: upper case for white, lower case for black. */
    public char fen() {
        return color == Color.WHITE ? Character.toUpperCase(type.fen()) : type.fen();
    }

    /** Reads one FEN letter: upper case means white, lower case means black. Throws for a non-piece character. */
    static Piece fromFen(char c) {
        return of(Character.isUpperCase(c) ? Color.WHITE : Color.BLACK, PieceType.fromFen(c));
    }

    /** True if this piece has exactly that colour and kind. A short way to ask "is this the black king?". */
    boolean is(Color c, PieceType t) {
        return color == c && type == t;
    }
}
