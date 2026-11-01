package com.lld.snakeladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The history is a complete event log: its rolls alone reproduce the game. */
class ReplayTest {

    private static final TurnRules ALL_SIX_RULES = new TurnRules(true, true);

    /** Test helper: plays a game to the end, with the given dice. */
    private static Game play(Dice dice) {
        Game game = Game.builder(Boards.dataGenetics()).players("ann", "bo", "cy")
                .dice(dice).turnRules(ALL_SIX_RULES).build();
        for (int turns = 0; game.winner().isEmpty(); turns++) {
            assertTrue(turns < 10_000, "fail-safe: a game this long means a bug");
            game.takeTurn();
        }
        return game;
    }

    /** Proves that feeding the logged rolls to scripted dice reproduces the game exactly. */
    @Test
    void replayingTheLoggedRollsReproducesTheGameExactly() {
        // seed 44 is the first seed from 40 up whose game contains a forfeit (42's has none)
        Game original = play(new FairDice(new SplittableRandom(44), 6));
        assertTrue(original.history().stream().anyMatch(TurnResult::forfeited),
                "the log must cover a forfeited turn, whose last roll moves nothing");
        int[] rolls = original.history().stream()
                .flatMap(turn -> turn.steps().stream())
                .mapToInt(Step::roll)
                .toArray();
        ScriptedDice script = new ScriptedDice(6, rolls);

        Game replay = play(script);

        assertEquals(original.history(), replay.history());
        assertEquals(original.winner(), replay.winner());
        assertEquals(0, script.remaining(), "the replay consumed exactly the logged rolls");
    }

    /** Proves that the same seed plays the same game. */
    @Test
    void theSameSeedPlaysTheSameGame() {
        List<TurnResult> first = play(new FairDice(new SplittableRandom(7), 6)).history();
        List<TurnResult> second = play(new FairDice(new SplittableRandom(7), 6)).history();

        assertEquals(first, second);
    }
}
