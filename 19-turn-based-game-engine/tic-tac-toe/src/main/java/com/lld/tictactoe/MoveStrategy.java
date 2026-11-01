package com.lld.tictactoe;

/**
 * Picks a move for the player whose turn it is (Strategy, Topic 05). Implementations don't mutate the game they're given.
 */
@FunctionalInterface
public interface MoveStrategy {

    /** @throws IllegalStateException if the game is already over */
    Cell choose(Game game);
}
