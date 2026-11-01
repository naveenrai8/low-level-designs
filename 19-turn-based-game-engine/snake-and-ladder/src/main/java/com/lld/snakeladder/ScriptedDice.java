package com.lld.snakeladder;

/** Returns a fixed sequence of rolls, then fails loudly. Used by tests and by replay. */
public final class ScriptedDice implements Dice {

    private final int faces;
    private final int[] rolls;
    private int next;

    /** Checks the faces and the rolls. Throws if a roll is not on the die. */
    public ScriptedDice(int faces, int... rolls) {
        if (faces < 2) {
            throw new IllegalArgumentException("a die needs at least 2 faces: " + faces);
        }
        for (int roll : rolls) {
            if (roll < 1 || roll > faces) {
                throw new IllegalArgumentException("roll " + roll + " is not a face of a " + faces + "-faced die");
            }
        }
        this.faces = faces;
        this.rolls = rolls.clone();
    }

    /** Gives the next roll from the script. Throws {@link IllegalStateException} when the script runs out. */
    @Override
    public int roll() {
        if (next == rolls.length) {
            throw new IllegalStateException("script exhausted after " + rolls.length + " rolls");
        }
        return rolls[next++];
    }

    /** Gives the number of faces on the die. */
    @Override
    public int faces() {
        return faces;
    }

    /** How many scripted rolls are still unused. */
    public int remaining() {
        return rolls.length - next;
    }
}
