package com.lld.snakeladder;

/**
 * Optional house rules about the top face ("a six" on a d6).
 *
 * @param extraTurnOnSix    rolling the top face gives the same player another roll in the same turn
 * @param threeSixesForfeit a third top face in one turn cancels the whole turn: the token goes back to where the turn started and play passes on. Needs {@code extraTurnOnSix}, because without it a turn has only one roll
 */
public record TurnRules(boolean extraTurnOnSix, boolean threeSixesForfeit) {

    /** One roll per turn, nothing special about a six. */
    public static final TurnRules NONE = new TurnRules(false, false);

    /** Refuses the forfeit rule without extra turns, since a turn would then have only one roll. */
    public TurnRules {
        if (threeSixesForfeit && !extraTurnOnSix) {
            throw new IllegalArgumentException("threeSixesForfeit needs extraTurnOnSix: a turn has only one roll otherwise");
        }
    }
}
