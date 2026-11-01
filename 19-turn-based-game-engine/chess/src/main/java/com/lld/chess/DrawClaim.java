package com.lld.chess;

/** A draw the player to move may claim but that does not happen on its own (FIDE 9.2, 9.3). */
public enum DrawClaim {
    THREEFOLD_REPETITION,
    FIFTY_MOVE_RULE
}
