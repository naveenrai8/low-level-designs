package com.lld.snakeladder;

import java.util.List;

/**
 * Everything that happened in one turn. The list of these is the game's event log: it holds every roll, so feeding those rolls to a {@link ScriptedDice} replays the game exactly.
 *
 * @param steps     every roll in the turn, in order (more than one only with {@link TurnRules#extraTurnOnSix})
 * @param start     the player's square when the turn began
 * @param end       the player's square when the turn ended ({@code start} again if forfeited)
 * @param forfeited true if a third top face cancelled the turn; the last step is that roll, with no movement
 * @param won       true if this turn reached the last square
 */
public record TurnResult(Player player, List<Step> steps, int start, int end, boolean forfeited, boolean won) {

    /** Takes a private copy of the steps so the log cannot be changed later. */
    public TurnResult {
        steps = List.copyOf(steps);
    }
}
