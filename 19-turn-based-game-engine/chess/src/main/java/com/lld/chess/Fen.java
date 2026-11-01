package com.lld.chess;

/** FEN (Forsyth-Edwards Notation, PGN standard §16.1) parsing and formatting, plus the validation that makes {@link Position} safe to build from untrusted text. */
final class Fen {

    /** Not instantiable; this class only holds static helpers. */
    private Fen() {
    }

    /**
     * Reads a FEN string into a validated {@link Position}. Impossible positions are rejected up front, so the rest of the engine can trust any position it gets.
     *
     * @throws IllegalArgumentException with a specific message if: not 4 or 6 fields; not 8 ranks of 8 files (no adjacent digits); a bad piece letter; not exactly one king per side; a pawn on rank 1 or 8; a bad side, castling, or e.p. field; a castling right without its king and rook at home; an e.p. square inconsistent with the board or with a non-zero halfmove clock; bad counters; or the side NOT to move is in check (the previous move would have been illegal)
     */
    static Position parse(String fen) {
        String[] f = fen.trim().split("\\s+");
        if (f.length != 4 && f.length != 6) {
            throw new IllegalArgumentException("FEN needs 4 or 6 fields, got " + f.length + ": " + fen);
        }
        Piece[] board = parsePlacement(f[0]);
        Color side = switch (f[1]) {
            case "w" -> Color.WHITE;
            case "b" -> Color.BLACK;
            default -> throw new IllegalArgumentException("side to move must be w or b: " + f[1]);
        };
        int rights = checkCastling(board, Castling.parse(f[2]));
        int ep = parseEnPassant(board, side, f[3]);
        int halfmove = f.length == 6 ? parseCounter(f[4], 0, "halfmove clock") : 0;
        int fullmove = f.length == 6 ? parseCounter(f[5], 1, "fullmove number") : 1;
        if (ep != Square.NONE && halfmove != 0) {
            throw new IllegalArgumentException("a double pawn push resets the halfmove clock, but it is "
                    + halfmove + ": " + fen);
        }

        int whiteKing = findKing(board, Color.WHITE);
        int blackKing = findKing(board, Color.BLACK);
        Position p = new Position(board, side, rights, ep, halfmove, fullmove, whiteKing, blackKing);
        if (MoveGenerator.isAttacked(p, p.kingSquare(side.opposite()), side)) {
            throw new IllegalArgumentException("the side not to move is in check: " + fen);
        }
        return p;
    }

    /** Writes a position as a six-field FEN string. Parsing the result gives back an equal position. */
    static String format(Position p) {
        return placement(p) + ' ' + p.sideToMove().fen() + ' ' + Castling.toFen(p.castlingRights()) + ' '
                + (p.enPassantSquare() == Square.NONE ? "-" : Square.name(p.enPassantSquare())) + ' '
                + p.halfmoveClock() + ' ' + p.fullmoveNumber();
    }

    /** Writes only the board part of FEN (the first field), from rank 8 down to rank 1 with digits for runs of empty squares. Also used as part of the repetition key. */
    static String placement(Position p) {
        StringBuilder sb = new StringBuilder(64);
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                Piece piece = p.at(Square.of(file, rank));
                if (piece == null) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                sb.append(piece.fen());
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (rank > 0) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    /** Reads the first FEN field into a 64-slot board. Rejects wrong rank counts, adjacent digits, overlong ranks and pawns on the first or last rank. */
    private static Piece[] parsePlacement(String field) {
        String[] ranks = field.split("/", -1);
        if (ranks.length != 8) {
            throw new IllegalArgumentException("placement needs 8 ranks, got " + ranks.length + ": " + field);
        }
        Piece[] board = new Piece[64];
        for (int i = 0; i < 8; i++) {
            int rank = 7 - i; // FEN lists rank 8 first
            int file = 0;
            boolean afterDigit = false;
            for (char c : ranks[i].toCharArray()) {
                if (c >= '1' && c <= '8') {
                    if (afterDigit) {
                        throw new IllegalArgumentException("rank " + (rank + 1) + " has adjacent digits: " + ranks[i]);
                    }
                    file += c - '0';
                    afterDigit = true;
                } else {
                    afterDigit = false;
                    if (file >= 8) {
                        throw new IllegalArgumentException("rank " + (rank + 1) + " has more than 8 files: " + ranks[i]);
                    }
                    Piece piece = Piece.fromFen(c);
                    if (piece.type() == PieceType.PAWN && (rank == 0 || rank == 7)) {
                        throw new IllegalArgumentException("pawn on rank " + (rank + 1) + ": " + field);
                    }
                    board[Square.of(file, rank)] = piece;
                    file++;
                }
            }
            if (file != 8) {
                throw new IllegalArgumentException("rank " + (rank + 1) + " has " + file + " files, not 8: " + ranks[i]);
            }
        }
        return board;
    }

    /** Finds the one king of this colour. Throws if there are none or more than one. */
    private static int findKing(Piece[] board, Color color) {
        int found = Square.NONE;
        for (int sq = 0; sq < 64; sq++) {
            if (board[sq] != null && board[sq].is(color, PieceType.KING)) {
                if (found != Square.NONE) {
                    throw new IllegalArgumentException("more than one " + color + " king");
                }
                found = sq;
            }
        }
        if (found == Square.NONE) {
            throw new IllegalArgumentException("no " + color + " king");
        }
        return found;
    }

    /** A right whose king or rook isn't on its home square can never be used, so it's rejected like an impossible e.p. square. Silently dropping it would make {@code toFen(fromFen(s)) != s}. */
    private static int checkCastling(Piece[] board, int rights) {
        for (Color c : Color.values()) {
            int rank = c == Color.WHITE ? 0 : 7;
            boolean kingHome = isPiece(board, Square.of(4, rank), c, PieceType.KING);
            checkRight(rights, Castling.kingside(c), kingHome && isPiece(board, Square.of(7, rank), c, PieceType.ROOK), c, "h");
            checkRight(rights, Castling.queenside(c), kingHome && isPiece(board, Square.of(0, rank), c, PieceType.ROOK), c, "a");
        }
        return rights;
    }

    /** Throws if a castling right is claimed but the king or rook is not on its home square. */
    private static void checkRight(int rights, int right, boolean piecesHome, Color c, String rookFile) {
        if ((rights & right) != 0 && !piecesHome) {
            String rank = c == Color.WHITE ? "1" : "8";
            throw new IllegalArgumentException("castling right " + Castling.toFen(right) + " needs the " + c
                    + " king on e" + rank + " and a rook on " + rookFile + rank);
        }
    }

    /** White to move ⇒ black just double-pushed: e.p. square on rank 6, black pawn in front of it, both squares it crossed empty. */
    private static int parseEnPassant(Piece[] board, Color side, String field) {
        if (field.equals("-")) {
            return Square.NONE;
        }
        int ep = Square.parse(field);
        if (ep == Square.NONE) {
            throw new IllegalArgumentException("bad en-passant square: " + field);
        }
        int expectedRank = side == Color.WHITE ? 5 : 2;
        if (Square.rank(ep) != expectedRank) {
            throw new IllegalArgumentException("en-passant square " + field + " is on the wrong rank for "
                    + (side == Color.WHITE ? "white" : "black") + " to move");
        }
        int file = Square.file(ep);
        int pusher = Square.of(file, expectedRank - side.forward());   // where the double-pushed pawn now stands
        int origin = Square.of(file, expectedRank + side.forward());   // where it started
        if (!isPiece(board, pusher, side.opposite(), PieceType.PAWN) || board[ep] != null || board[origin] != null) {
            throw new IllegalArgumentException("en-passant square " + field + " doesn't follow a double pawn push");
        }
        return ep;
    }

    /** Reads the halfmove or fullmove counter. Throws if it is not a number or is below the minimum. */
    private static int parseCounter(String s, int min, String what) {
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a number: " + s, e);
        }
        if (n < min) {
            throw new IllegalArgumentException(what + " must be >= " + min + ": " + s);
        }
        return n;
    }

    /** True if the square holds exactly this colour and kind of piece. */
    private static boolean isPiece(Piece[] board, int sq, Color c, PieceType t) {
        return board[sq] != null && board[sq].is(c, t);
    }
}
