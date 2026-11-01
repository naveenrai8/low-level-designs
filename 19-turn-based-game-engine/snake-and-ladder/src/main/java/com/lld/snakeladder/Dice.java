package com.lld.snakeladder;

/**
 * The only source of randomness in a game. Injected, so tests script every roll and a seeded {@link FairDice} makes a whole simulation reproducible.
 */
public interface Dice {

    /** A value in {@code 1..faces()}. */
    int roll();

    /** Number of faces. The top face ({@code faces()}) is what the "six" rules in {@link TurnRules} mean. */
    int faces();
}
