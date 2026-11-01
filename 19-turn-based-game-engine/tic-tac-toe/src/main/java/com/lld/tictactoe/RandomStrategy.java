package com.lld.tictactoe;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** A uniformly random empty cell. The generator is injected, so a seed makes games repeatable. */
public final class RandomStrategy implements MoveStrategy {

    private final RandomGenerator random;

    /** Keeps the injected random source. A seeded one makes games repeatable. */
    public RandomStrategy(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Picks a random empty cell. Throws {@link IllegalStateException} if the game is over. */
    @Override
    public Cell choose(Game game) {
        if (game.status() != Status.IN_PROGRESS) {
            throw new IllegalStateException("game is over: " + game.status());
        }
        List<Cell> empty = game.emptyCells();
        return empty.get(random.nextInt(empty.size()));
    }
}
