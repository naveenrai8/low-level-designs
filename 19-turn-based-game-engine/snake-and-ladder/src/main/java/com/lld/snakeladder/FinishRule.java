package com.lld.snakeladder;

/**
 * What happens when a roll would take the token past the last square. An enum with behaviour: three fixed variants, each a one-line rule, so a Strategy interface would add nothing (Topic 05).
 *
 * <p>{@link #land} returns the square the token lands on <em>before</em> any jump. The caller then applies {@link Board#destination}. For {@link #EXACT_OR_STAY} the token stays on a square it was already resting on, which is never a jump start, so "staying" never triggers a jump.
 */
public enum FinishRule {

    /** Reaching <em>or passing</em> the last square wins. The DataGenetics house rule and the usual analysis assumption. */
    OVERSHOOT_WINS {
        /** Overshoot wins: a roll that goes past the end simply stops on the last square. */
        @Override
        int land(int from, int roll, int lastSquare) {
            return Math.min(from + roll, lastSquare);
        }
    },

    /** You must land exactly on the last square. A roll that would overshoot is lost and the token stays. */
    EXACT_OR_STAY {
        /** Exact or stay: a roll that goes past the end leaves the token where it is. */
        @Override
        int land(int from, int roll, int lastSquare) {
            int target = from + roll;
            return target > lastSquare ? from : target;
        }
    },

    /** You must land exactly. The excess is walked back from the last square, and a jump there applies. */
    BOUNCE_BACK {
        /** Bounce back: a roll that goes past the end walks the extra steps back from the last square. */
        @Override
        int land(int from, int roll, int lastSquare) {
            int target = from + roll;
            return target > lastSquare ? 2 * lastSquare - target : target;
        }

        /** Bounce back must not have more faces than squares, or the token could bounce off the board. */
        @Override
        void checkDice(int lastSquare, int faces) {
            // from <= last-1 and roll <= faces, so the bounce lands on >= last+1-faces. faces <= last keeps it on the board.
            if (faces > lastSquare) {
                throw new IllegalArgumentException(
                        "BOUNCE_BACK needs no more dice faces (" + faces + ") than squares (" + lastSquare + ")");
            }
        }
    };

    /** Works out the square a token lands on before any jump. */
    abstract int land(int from, int roll, int lastSquare);

    /** Checks that the die works with the board for this rule. The default accepts every die. */
    void checkDice(int lastSquare, int faces) {
    }
}
