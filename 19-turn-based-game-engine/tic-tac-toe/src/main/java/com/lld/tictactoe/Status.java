package com.lld.tictactoe;

/** Lifecycle of a {@link Game}. {@link Game#undo} is the only way back to {@code IN_PROGRESS}. */
public enum Status {
    IN_PROGRESS,
    WON,
    DRAW
}
