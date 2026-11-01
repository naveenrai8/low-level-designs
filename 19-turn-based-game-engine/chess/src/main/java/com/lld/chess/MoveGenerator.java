package com.lld.chess;

import static com.lld.chess.Move.Kind.CAPTURE;
import static com.lld.chess.Move.Kind.CASTLE_KINGSIDE;
import static com.lld.chess.Move.Kind.CASTLE_QUEENSIDE;
import static com.lld.chess.Move.Kind.DOUBLE_PUSH;
import static com.lld.chess.Move.Kind.EN_PASSANT;
import static com.lld.chess.Move.Kind.QUIET;

import java.util.ArrayList;
import java.util.List;

/**
 * Pseudo-legal move generation and attack detection over the mailbox.
 *
 * <p>"Pseudo-legal" means: every move the pieces can physically make, <b>ignoring whether it leaves the mover's own king in check</b>. {@link Position#legalMoves()} filters these by playing each one and asking "is my king attacked now?". That one filter handles pins, moving into check, check evasions and the en-passant horizontal pin, with no special cases. The only legality rule applied here is castling's "not out of or through check", because the filter only sees where the king ends up, not the squares it crossed.
 *
 * <p>Pieces are data: (file, rank) direction tables plus "slides or steps". Pawns and castling are the only explicit code, because they're the only moves that depend on more than geometry.
 */
final class MoveGenerator {

    static final int[][] KNIGHT_STEPS = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    static final int[][] KING_STEPS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    static final int[][] ORTHOGONAL = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    static final int[][] DIAGONAL = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};

    private static final PieceType[] PROMOTIONS = {PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT};

    /** Not instantiable; this class only holds static helpers. */
    private MoveGenerator() {
    }

    /** Lists every move the side to move can physically make, including ones that leave its own king in check. {@link Position#legalMoves()} filters those out afterwards. */
    static List<Move> pseudoLegal(Position p) {
        List<Move> moves = new ArrayList<>(48);
        Color side = p.sideToMove();
        for (int sq = 0; sq < 64; sq++) {
            Piece piece = p.at(sq);
            if (piece == null || piece.color() != side) {
                continue;
            }
            switch (piece.type()) {
                case PAWN -> pawnMoves(p, sq, side, moves);
                case KNIGHT -> steps(p, sq, side, KNIGHT_STEPS, moves);
                case BISHOP -> slides(p, sq, side, DIAGONAL, moves);
                case ROOK -> slides(p, sq, side, ORTHOGONAL, moves);
                case QUEEN -> {
                    slides(p, sq, side, ORTHOGONAL, moves);
                    slides(p, sq, side, DIAGONAL, moves);
                }
                case KING -> {
                    steps(p, sq, side, KING_STEPS, moves);
                    castling(p, sq, side, moves);
                }
            }
        }
        return moves;
    }

    /** Adds the pawn moves from one square: single and double pushes, diagonal captures, en passant and promotions. Pawns need their own code because they move and capture in different ways. */
    private static void pawnMoves(Position p, int from, Color side, List<Move> moves) {
        int file = Square.file(from);
        int rank = Square.rank(from);
        int ahead = rank + side.forward(); // always on the board: no pawn stands on its own last rank
        int startRank = side == Color.WHITE ? 1 : 6;

        int one = Square.of(file, ahead);
        if (p.at(one) == null) {
            addPawnMove(from, one, QUIET, moves);
            int two = Square.of(file, ahead + side.forward());
            if (rank == startRank && p.at(two) == null) {
                moves.add(Move.of(from, two, DOUBLE_PUSH));
            }
        }
        for (int df = -1; df <= 1; df += 2) {
            if (!Square.onBoard(file + df, ahead)) {
                continue;
            }
            int to = Square.of(file + df, ahead);
            Piece target = p.at(to);
            if (target != null && target.color() != side) {
                addPawnMove(from, to, CAPTURE, moves);
            } else if (to == p.enPassantSquare()
                    && p.at(Square.of(file + df, rank)) != null
                    && p.at(Square.of(file + df, rank)).is(side.opposite(), PieceType.PAWN)) {
                // the captured pawn is NOT on 'to': it's beside us, on (to's file, from's rank)
                moves.add(Move.of(from, to, EN_PASSANT));
            }
        }
    }

    /** Adds a pawn move, or four moves (queen, rook, bishop, knight) if it lands on the last rank. Each promotion piece is a separate move. */
    private static void addPawnMove(int from, int to, Move.Kind kind, List<Move> moves) {
        int lastRank = Square.rank(to);
        if (lastRank == 0 || lastRank == 7) {
            for (PieceType promotion : PROMOTIONS) {
                moves.add(new Move(from, to, kind, promotion)); // capture-promotions too: Position 4 d2 = 264 checks it
            }
        } else {
            moves.add(Move.of(from, to, kind));
        }
    }

    /** Adds moves for a piece that jumps a fixed distance (knight, king). A jump is blocked only by a friendly piece on the target square. */
    private static void steps(Position p, int from, Color side, int[][] deltas, List<Move> moves) {
        int file = Square.file(from);
        int rank = Square.rank(from);
        for (int[] d : deltas) {
            if (!Square.onBoard(file + d[0], rank + d[1])) {
                continue;
            }
            int to = Square.of(file + d[0], rank + d[1]);
            Piece target = p.at(to);
            if (target == null) {
                moves.add(Move.of(from, to, QUIET));
            } else if (target.color() != side) {
                moves.add(Move.of(from, to, CAPTURE));
            }
        }
    }

    /** Adds moves for a piece that slides along rays (bishop, rook, queen). Each ray stops at the first piece, and that square is a capture if the piece is an enemy. */
    private static void slides(Position p, int from, Color side, int[][] directions, List<Move> moves) {
        for (int[] d : directions) {
            int f = Square.file(from) + d[0];
            int r = Square.rank(from) + d[1];
            while (Square.onBoard(f, r)) {
                int to = Square.of(f, r);
                Piece target = p.at(to);
                if (target == null) {
                    moves.add(Move.of(from, to, QUIET));
                } else {
                    if (target.color() != side) {
                        moves.add(Move.of(from, to, CAPTURE));
                    }
                    break;
                }
                f += d[0];
                r += d[1];
            }
        }
    }

    /** FIDE 3.8.2: the king and rook are unmoved (the rights bit + both on their home squares), the squares between them are empty, and the king is not in check and doesn't cross or land on an attacked square. The rook's path may be attacked: on the queen side b1 must be empty, not safe. */
    /** Adds castling moves if allowed. See the comment above for the rules. The rights bit is not enough: the rook must still stand on its home square too. */
    private static void castling(Position p, int kingSquare, Color side, List<Move> moves) {
        int home = side == Color.WHITE ? Castling.E1 : Castling.E8;
        if (kingSquare != home) {
            return;
        }
        int rank = Square.rank(home);
        Color enemy = side.opposite();
        if ((p.castlingRights() & Castling.kingside(side)) != 0
                && isOwnRook(p, Square.of(7, rank), side)
                && p.at(Square.of(5, rank)) == null && p.at(Square.of(6, rank)) == null
                && !isAttacked(p, home, enemy)
                && !isAttacked(p, Square.of(5, rank), enemy)
                && !isAttacked(p, Square.of(6, rank), enemy)) {
            moves.add(Move.of(home, Square.of(6, rank), CASTLE_KINGSIDE));
        }
        if ((p.castlingRights() & Castling.queenside(side)) != 0
                && isOwnRook(p, Square.of(0, rank), side)
                && p.at(Square.of(1, rank)) == null && p.at(Square.of(2, rank)) == null
                && p.at(Square.of(3, rank)) == null
                && !isAttacked(p, home, enemy)
                && !isAttacked(p, Square.of(3, rank), enemy)
                && !isAttacked(p, Square.of(2, rank), enemy)) {
            moves.add(Move.of(home, Square.of(2, rank), CASTLE_QUEENSIDE));
        }
    }

    /** True if the square holds a rook of the given colour. */
    private static boolean isOwnRook(Position p, int square, Color side) {
        Piece piece = p.at(square);
        return piece != null && piece.is(side, PieceType.ROOK);
    }

    /** Is {@code square} attacked by any piece of colour {@code by}? Looks outward from the square. */
    static boolean isAttacked(Position p, int square, Color by) {
        int file = Square.file(square);
        int rank = Square.rank(square);

        // A pawn of `by` attacks this square from one rank "behind" it, on an adjacent file.
        int pawnRank = rank - by.forward();
        for (int df = -1; df <= 1; df += 2) {
            if (Square.onBoard(file + df, pawnRank) && isPiece(p, Square.of(file + df, pawnRank), by, PieceType.PAWN)) {
                return true;
            }
        }
        if (stepAttack(p, file, rank, by, KNIGHT_STEPS, PieceType.KNIGHT)
                || stepAttack(p, file, rank, by, KING_STEPS, PieceType.KING)) {
            return true;
        }
        return slideAttack(p, file, rank, by, ORTHOGONAL, PieceType.ROOK)
                || slideAttack(p, file, rank, by, DIAGONAL, PieceType.BISHOP);
    }

    /** True if a piece of the given kind and colour stands one jump away from the square (used for knights and kings). */
    private static boolean stepAttack(Position p, int file, int rank, Color by, int[][] deltas, PieceType type) {
        for (int[] d : deltas) {
            if (Square.onBoard(file + d[0], rank + d[1]) && isPiece(p, Square.of(file + d[0], rank + d[1]), by, type)) {
                return true;
            }
        }
        return false;
    }

    /** The first piece met along each ray attacks if it's {@code by}'s slider of this kind or a queen. */
    private static boolean slideAttack(Position p, int file, int rank, Color by, int[][] directions, PieceType slider) {
        for (int[] d : directions) {
            int f = file + d[0];
            int r = rank + d[1];
            while (Square.onBoard(f, r)) {
                Piece piece = p.at(Square.of(f, r));
                if (piece != null) {
                    if (piece.color() == by && (piece.type() == slider || piece.type() == PieceType.QUEEN)) {
                        return true;
                    }
                    break;
                }
                f += d[0];
                r += d[1];
            }
        }
        return false;
    }

    /** True if the square holds exactly this colour and kind of piece. */
    private static boolean isPiece(Position p, int square, Color color, PieceType type) {
        Piece piece = p.at(square);
        return piece != null && piece.is(color, type);
    }
}
