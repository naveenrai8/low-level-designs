package com.lld.chess;

/** The outcome of {@link Game#claimDraw()}. It is its own type rather than {@link MoveResult}, because a claim isn't a move, so there is no {@link Move} to report, and the rejection reasons don't overlap. */
public sealed interface ClaimResult {

    /** The claim was valid, so the game ended with the given draw status. */
    record Claimed(Status status) implements ClaimResult {
    }

    /** The claim was refused, and the game carries on unchanged. */
    record Rejected(Reason reason) implements ClaimResult {
    }

    /** Why a draw claim was refused. */
    enum Reason {
        /** The game had already ended, so there is nothing left to claim. */
        GAME_OVER,
        /** Neither threefold repetition nor the 50-move rule applies to the position on the board. */
        NO_DRAW_TO_CLAIM
    }
}
