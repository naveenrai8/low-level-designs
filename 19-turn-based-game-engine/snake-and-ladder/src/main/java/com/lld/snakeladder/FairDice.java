package com.lld.snakeladder;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** A uniform die over an injected generator. Pass {@code new SplittableRandom(seed)} for reproducible runs. */
public final class FairDice implements Dice {

    private final RandomGenerator random;
    private final int faces;

    /** Keeps the random source and the number of faces. Throws if there are fewer than 2 faces. */
    public FairDice(RandomGenerator random, int faces) {
        this.random = Objects.requireNonNull(random, "random");
        if (faces < 2) {
            throw new IllegalArgumentException("a die needs at least 2 faces: " + faces);
        }
        this.faces = faces;
    }

    /** Rolls a number from 1 to the number of faces, all equally likely. */
    @Override
    public int roll() {
        return random.nextInt(1, faces + 1);
    }

    /** Gives the number of faces on the die. */
    @Override
    public int faces() {
        return faces;
    }
}
