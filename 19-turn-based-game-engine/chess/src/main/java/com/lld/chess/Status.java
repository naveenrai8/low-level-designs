package com.lld.chess;

/** Where the game stands. The first five terminal values end the game <b>automatically</b> (FIDE 5.1, 5.2, 9.6); the last two only after a player claims them via {@link Game#claimDraw()} (FIDE 9.2, 9.3). */
public enum Status {
    IN_PROGRESS,
    CHECKMATE,
    STALEMATE,
    DRAW_INSUFFICIENT_MATERIAL,
    DRAW_FIVEFOLD_REPETITION,
    DRAW_SEVENTY_FIVE_MOVE,
    DRAW_THREEFOLD_REPETITION,
    DRAW_FIFTY_MOVE;

    /** True once the game has ended, for any reason. Only {@code IN_PROGRESS} is not over. */
    public boolean isOver() {
        return this != IN_PROGRESS;
    }

    /** True for every ending except checkmate. Stalemate counts as a draw, because nobody wins. */
    public boolean isDraw() {
        return isOver() && this != CHECKMATE;
    }
}
