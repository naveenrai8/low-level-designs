package com.lld.chess;

import java.util.Arrays;

/**
 * Castling rights as a 4-bit mask, updated with one table lookup per move.
 *
 * <p>The trick: {@code rights &= KEEP[from] & KEEP[to]}. A move <b>from</b> e1 (king) clears both white rights; from h1 (rook) clears white king-side. Because the mask is also applied to {@code to}, <b>capturing a rook on its home square</b> clears the victim's right, which is the case most hand-written versions forget. Rights are only ever lost, never regained.
 */
final class Castling {

    static final int WHITE_KINGSIDE = 1;
    static final int WHITE_QUEENSIDE = 2;
    static final int BLACK_KINGSIDE = 4;
    static final int BLACK_QUEENSIDE = 8;
    static final int NONE = 0;

    static final int E1 = Square.of(4, 0);
    static final int E8 = Square.of(4, 7);

    private static final int ALL = 15;
    private static final int[] KEEP = new int[64];

    static {
        Arrays.fill(KEEP, ALL);
        KEEP[E1] &= ~(WHITE_KINGSIDE | WHITE_QUEENSIDE);
        KEEP[Square.of(7, 0)] &= ~WHITE_KINGSIDE;
        KEEP[Square.of(0, 0)] &= ~WHITE_QUEENSIDE;
        KEEP[E8] &= ~(BLACK_KINGSIDE | BLACK_QUEENSIDE);
        KEEP[Square.of(7, 7)] &= ~BLACK_KINGSIDE;
        KEEP[Square.of(0, 7)] &= ~BLACK_QUEENSIDE;
    }

    /** Not instantiable; this class only holds static helpers. */
    private Castling() {
    }

    /** Returns the rights left after a move between these squares. Looks at both squares, so moving the king, moving a rook, or capturing a rook all clear the right. */
    static int afterMove(int rights, int from, int to) {
        return rights & KEEP[from] & KEEP[to];
    }

    /** The king-side right bit for this colour. */
    static int kingside(Color c) {
        return c == Color.WHITE ? WHITE_KINGSIDE : BLACK_KINGSIDE;
    }

    /** The queen-side right bit for this colour. */
    static int queenside(Color c) {
        return c == Color.WHITE ? WHITE_QUEENSIDE : BLACK_QUEENSIDE;
    }

    /** Writes the rights as the FEN field, such as "KQkq", or "-" when none are left. */
    static String toFen(int rights) {
        if (rights == NONE) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(4);
        if ((rights & WHITE_KINGSIDE) != 0) sb.append('K');
        if ((rights & WHITE_QUEENSIDE) != 0) sb.append('Q');
        if ((rights & BLACK_KINGSIDE) != 0) sb.append('k');
        if ((rights & BLACK_QUEENSIDE) != 0) sb.append('q');
        return sb.toString();
    }

    /** Reads the FEN castling field into a mask. Throws {@code IllegalArgumentException} for an unknown letter or a repeated one. */
    static int parse(String field) {
        if (field.equals("-")) {
            return NONE;
        }
        int rights = NONE;
        for (char c : field.toCharArray()) {
            int bit = switch (c) {
                case 'K' -> WHITE_KINGSIDE;
                case 'Q' -> WHITE_QUEENSIDE;
                case 'k' -> BLACK_KINGSIDE;
                case 'q' -> BLACK_QUEENSIDE;
                default -> throw new IllegalArgumentException("bad castling field: " + field);
            };
            if ((rights & bit) != 0) {
                throw new IllegalArgumentException("duplicate castling right in: " + field);
            }
            rights |= bit;
        }
        return rights;
    }
}
