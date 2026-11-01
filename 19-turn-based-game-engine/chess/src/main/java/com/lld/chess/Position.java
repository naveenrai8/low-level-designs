package com.lld.chess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * An immutable chess position: placement, side to move, castling rights, en-passant square and the two move counters (everything a FEN string holds).
 *
 * <p><b>Copy-make.</b> {@link #play(Move)} returns a <i>new</i> position and never mutates this one. So undo is "pop the previous position", the repetition history is just the list of positions, and a position can be shared across threads with no locking. The cost is copying a 64-slot array per move, measured as fine at the perft depths in the tests (see {@code commands.md}). Engines use make/unmake on one mutable board instead (README F3).
 *
 * <p>The board is a 64-slot mailbox, {@code a1 = 0 ... h8 = 63}.
 */
public final class Position {

    /** The standard starting position. */
    public static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private final Piece[] board;
    private final Color sideToMove;
    private final int castlingRights;
    private final int enPassantSquare;
    private final int halfmoveClock;
    private final int fullmoveNumber;
    private final int whiteKing;
    private final int blackKing;

    /** Trusted: {@code board} is owned by the new position (not copied) and already validated. */
    Position(Piece[] board, Color sideToMove, int castlingRights, int enPassantSquare,
             int halfmoveClock, int fullmoveNumber, int whiteKing, int blackKing) {
        this.board = board;
        this.sideToMove = sideToMove;
        this.castlingRights = castlingRights;
        this.enPassantSquare = enPassantSquare;
        this.halfmoveClock = halfmoveClock;
        this.fullmoveNumber = fullmoveNumber;
        this.whiteKing = whiteKing;
        this.blackKing = blackKing;
    }

    /**
     * Parses a FEN string. Accepts the 4-field form (halfmove and fullmove default to {@code 0 1}).
     *
     * @throws IllegalArgumentException if the string is malformed or the position is illegal (see {@link Fen#parse})
     * @throws NullPointerException if {@code fen} is null: FEN is configuration, so null is a programmer error, not input to report
     */
    public static Position fromFen(String fen) {
        return Fen.parse(fen);
    }

    /** The standard starting position. */
    public static Position start() {
        return Fen.parse(START_FEN);
    }

    /** This position as a six-field FEN string. */
    public String toFen() {
        return Fen.format(this);
    }

    /** All legal moves for the side to move. Empty means checkmate (if {@link #inCheck()}) or stalemate. */
    public List<Move> legalMoves() {
        List<Move> legal = new ArrayList<>();
        for (Move m : MoveGenerator.pseudoLegal(this)) {
            if (playIfLegal(m) != null) {
                legal.add(m);
            }
        }
        return Collections.unmodifiableList(legal);
    }

    /** True if the side to move has its king under attack right now. */
    public boolean inCheck() {
        return MoveGenerator.isAttacked(this, kingSquare(sideToMove), sideToMove.opposite());
    }

    /**
     * True if any piece of colour {@code by} attacks the square.
     *
     * @param square a square name such as {@code "f1"}
     */
    public boolean isAttacked(String square, Color by) {
        return MoveGenerator.isAttacked(this, parseSquare(square), by);
    }

    /** The piece on a square such as "e4", or empty if the square is empty. Throws for a name that is not a square. */
    public Optional<Piece> pieceAt(String square) {
        return Optional.ofNullable(board[parseSquare(square)]);
    }

    /** Whose turn it is. */
    public Color sideToMove() {
        return sideToMove;
    }

    /** Moves since the last pawn move or capture, counted in single moves (plies). Reaching 100 allows a draw claim. */
    public int halfmoveClock() {
        return halfmoveClock;
    }

    /** The move number, starting at 1 and going up after each black move. */
    public int fullmoveNumber() {
        return fullmoveNumber;
    }

    /**
     * The identity used for repetition (FIDE 9.2.3): placement, side to move, castling rights, and the en-passant square <b>only if an en-passant capture is actually legal</b>.
     *
     * <p>FEN records the e.p. square after <i>every</i> double push (PGN §16.1.3.4), so comparing raw FENs would call two identical positions different just because one followed a double push that nobody could capture. A hash (Zobrist) is the engine version of this key; a string is plenty for a game history of a few hundred plies.
     */
    public String repetitionKey() {
        boolean epCapturable = enPassantSquare != Square.NONE
                && legalMoves().stream().anyMatch(m -> m.kind() == Move.Kind.EN_PASSANT);
        return Fen.placement(this) + ' ' + sideToMove.fen() + ' ' + Castling.toFen(castlingRights) + ' '
                + (epCapturable ? Square.name(enPassantSquare) : "-");
    }

    /** FIDE 5.2.2 calls a position "dead" when no sequence of legal moves can mate. This checks the standard material-only subset: K v K, K + one minor v K, and kings plus bishops that all stand on one square colour. K+N+N v K is <b>not</b> dead (a mate exists if the defender blunders). Positions that are dead only because of a locked pawn structure aren't detected. */
    public boolean hasInsufficientMaterial() {
        int minors = 0;
        boolean knight = false;
        boolean lightBishop = false;
        boolean darkBishop = false;
        for (int sq = 0; sq < 64; sq++) {
            Piece p = board[sq];
            if (p == null) {
                continue;
            }
            switch (p.type()) {
                case PAWN, ROOK, QUEEN -> {
                    return false;
                }
                case KNIGHT -> {
                    knight = true;
                    minors++;
                }
                case BISHOP -> {
                    minors++;
                    if (Square.isLight(sq)) {
                        lightBishop = true;
                    } else {
                        darkBishop = true;
                    }
                }
                case KING -> { }
            }
        }
        return minors <= 1 || (!knight && !(lightBishop && darkBishop));
    }

    // ---- package-private: the engine room -------------------------------------------------

    /** The piece on a square index, or null if empty. */
    Piece at(int square) {
        return board[square];
    }

    /** The castling-rights bit mask (see {@link Castling}). */
    int castlingRights() {
        return castlingRights;
    }

    /** The square a pawn just skipped over with a double push, or {@link Square#NONE}. */
    int enPassantSquare() {
        return enPassantSquare;
    }

    /** Where the king of this colour stands. Kept as a field so check detection does not need to search the board. */
    int kingSquare(Color c) {
        return c == Color.WHITE ? whiteKing : blackKing;
    }

    /** Plays {@code m}, trusting that it's pseudo-legal here (it came from the generator). Package private on purpose: the public way to move is {@link Game#play(String)}, which validates. */
    Position play(Move m) {
        Piece[] b = board.clone();
        Piece moving = b[m.from()];
        b[m.from()] = null;
        b[m.to()] = m.promotion() == null ? moving : Piece.of(sideToMove, m.promotion());
        int rank = Square.rank(m.from());
        switch (m.kind()) {
            case EN_PASSANT -> b[Square.of(Square.file(m.to()), rank)] = null; // the pawn beside us
            case CASTLE_KINGSIDE -> moveRook(b, Square.of(7, rank), Square.of(5, rank));
            case CASTLE_QUEENSIDE -> moveRook(b, Square.of(0, rank), Square.of(3, rank));
            default -> { }
        }
        int wk = whiteKing;
        int bk = blackKing;
        if (moving.type() == PieceType.KING) {
            if (sideToMove == Color.WHITE) {
                wk = m.to();
            } else {
                bk = m.to();
            }
        }
        // FIDE 9.3: the 50-move count resets on any pawn move or capture (incl. e.p. and capture-promotions).
        boolean resetsClock = moving.type() == PieceType.PAWN || m.isCapture();
        return new Position(
                b,
                sideToMove.opposite(),
                Castling.afterMove(castlingRights, m.from(), m.to()),
                m.kind() == Move.Kind.DOUBLE_PUSH ? (m.from() + m.to()) / 2 : Square.NONE,
                resetsClock ? 0 : halfmoveClock + 1,
                sideToMove == Color.BLACK ? fullmoveNumber + 1 : fullmoveNumber,
                wk,
                bk);
    }

    /** {@link #play} if the mover's king is safe afterwards, else {@code null}. The legality filter. */
    Position playIfLegal(Move m) {
        Position next = play(m);
        return MoveGenerator.isAttacked(next, next.kingSquare(sideToMove), sideToMove.opposite()) ? null : next;
    }

    /** Moves the rook during castling. */
    private static void moveRook(Piece[] b, int from, int to) {
        b[to] = b[from];
        b[from] = null;
    }

    /** Turns a square name into an index. Throws {@code IllegalArgumentException} for a bad name. */
    private static int parseSquare(String square) {
        int sq = Square.parse(square);
        if (sq == Square.NONE) {
            throw new IllegalArgumentException("not a square: " + square);
        }
        return sq;
    }

    @Override
    /** Two positions are equal when their FEN strings match, counters included. */
    public boolean equals(Object o) {
        return o instanceof Position other && toFen().equals(other.toFen());
    }

    @Override
    /** Hash of the FEN string, to agree with {@code equals}. */
    public int hashCode() {
        return toFen().hashCode();
    }

    @Override
    /** The FEN string, which is easy to read in test failures. */
    public String toString() {
        return toFen();
    }
}
