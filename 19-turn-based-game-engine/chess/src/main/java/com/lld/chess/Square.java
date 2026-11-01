package com.lld.chess;

/** Square arithmetic for the 64-slot mailbox: {@code a1 = 0, b1 = 1, ..., h1 = 7, a2 = 8, ..., h8 = 63}. Moving off the board is caught by checking file and rank, not by the index (an index {@code +1} from h1 would silently land on a2). */
final class Square {

    static final int NONE = -1;

    /** Not instantiable; this class only holds static helpers. */
    private Square() {
    }

    /** Builds a square index from a file (0 = a) and a rank (0 = rank 1). */
    static int of(int file, int rank) {
        return rank * 8 + file;
    }

    /** The file (column) of a square, 0 for a to 7 for h. */
    static int file(int square) {
        return square & 7;
    }

    /** The rank (row) of a square, 0 for rank 1 to 7 for rank 8. */
    static int rank(int square) {
        return square >> 3;
    }

    /** True if the file and rank are both inside the board. Use this before indexing, never the raw index. */
    static boolean onBoard(int file, int rank) {
        return file >= 0 && file < 8 && rank >= 0 && rank < 8;
    }

    /** True for a light square. Bishops stay on one colour, so insufficient-material checks need this. */
    static boolean isLight(int square) {
        return (file(square) + rank(square)) % 2 == 1; // a1 is dark
    }

    /** The algebraic name of a square, such as "e4". */
    static String name(int square) {
        return "" + (char) ('a' + file(square)) + (char) ('1' + rank(square));
    }

    /**
     * Turns a square name like "e4" into its index. Returns {@link #NONE} instead of throwing, so callers choose how to react.
     *
     * @return the square index, or {@link #NONE} if {@code s} isn't a square name like "e4"
     */
    static int parse(String s) {
        if (s.length() != 2) {
            return NONE;
        }
        int file = s.charAt(0) - 'a';
        int rank = s.charAt(1) - '1';
        return onBoard(file, rank) ? of(file, rank) : NONE;
    }
}
