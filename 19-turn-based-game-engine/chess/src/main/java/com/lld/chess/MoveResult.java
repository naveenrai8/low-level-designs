package com.lld.chess;

/** The outcome of {@link Game#play(String)}. Bad input is an expected outcome in a game (a user mistypes, an opponent tries an illegal move), so it's a value, not an exception. */
public sealed interface MoveResult {

    /** The move was legal and has been played. Carries the move and the game status after it. */
    record Applied(Move move, Status status) implements MoveResult {
    }

    /** The move was refused, and the game is unchanged. */
    record Rejected(Reason reason) implements MoveResult {
    }

    /** Why a move was refused. */
    enum Reason {
        /** Not UCI syntax: four lowercase square characters plus an optional {@code q|r|b|n}. */
        MALFORMED,
        /** Well-formed but not in the legal-move list (wrong piece, pinned, leaves king in check...). */
        ILLEGAL,
        /** A pawn reached the last rank but no piece was named: {@code e7e8} instead of {@code e7e8q}. */
        PROMOTION_PIECE_REQUIRED,
        /** The game had already ended, so no more moves are accepted. */
        GAME_OVER
    }
}
