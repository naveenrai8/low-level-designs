package com.lld.snakeladder;

/** A ladder ({@code to > from}) or a snake ({@code to < from}). Landing on {@code from} moves the token to {@code to}. */
public record Jump(int from, int to) {

    /** Checks that the jump actually moves the token. */
    public Jump {
        if (from == to) {
            throw new IllegalArgumentException("a jump must move the token: " + from + "->" + to);
        }
    }

    /** True for a ladder, false for a snake. */
    public boolean isLadder() {
        return to > from;
    }
}
