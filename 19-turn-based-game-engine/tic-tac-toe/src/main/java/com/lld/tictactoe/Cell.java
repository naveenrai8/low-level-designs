package com.lld.tictactoe;

/** A board coordinate, zero-based. Bounds are checked by {@link Game}, which knows the size. */
public record Cell(int row, int col) {}
