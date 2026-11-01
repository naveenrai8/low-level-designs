package com.lld.tictactoe;

import java.util.Objects;

/**
 * One entry in a game's move log. This is "Command lite" (Topic 05): a record the game can re-apply ({@link Game#replay}) or reverse ({@link Game#undo}), because it captures everything the reversal needs: who moved and where.
 */
public record Move(Player player, Cell cell) {

    /** Checks the rules once, when the record is built. A move needs both a player and a cell. */
    public Move {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cell, "cell");
    }
}
