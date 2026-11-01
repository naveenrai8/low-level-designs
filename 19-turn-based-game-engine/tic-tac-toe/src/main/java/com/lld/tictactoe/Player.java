package com.lld.tictactoe;

import java.util.Objects;

/**
 * A seat at the table: a stable id (what callers submit moves as) and the symbol drawn on the board.
 *
 * @param id non-blank, unique within a {@link GameConfig}
 * @param symbol not whitespace and not {@link Game#EMPTY}, unique within a {@link GameConfig}
 */
public record Player(String id, char symbol) {

    /** Checks the id and symbol once, when the record is built. A bad player fails here instead of causing odd games later. */
    public Player {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("player id must be non-blank");
        }
        if (Character.isWhitespace(symbol) || symbol == Game.EMPTY) {
            throw new IllegalArgumentException("symbol must be visible and not '" + Game.EMPTY + "': '" + symbol + "'");
        }
    }
}
