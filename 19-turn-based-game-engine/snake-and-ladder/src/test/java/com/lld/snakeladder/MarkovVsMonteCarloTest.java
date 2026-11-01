package com.lld.snakeladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The exact Markov answer and a seeded simulation through the real {@link Game} must agree within 4 standard errors. They share only the board and {@link FinishRule}, so agreement checks the game loop, the dice and the solver against each other.
 */
class MarkovVsMonteCarloTest {

    private static final int GAMES = 200_000;
    private static final int CAP = 10_000;

    /** Test helper: runs many seeded games and returns the average rolls. */
    private static SimulationResult simulate(Board board, FinishRule rule, TurnRules turnRules, long seed) {
        return Simulation.run(board, rule, turnRules, new FairDice(new SplittableRandom(seed), 6), GAMES, CAP);
    }

    /** Proves the simulated average is close to the maths answer, within a stated tolerance. */
    private static void assertAgrees(double markov, SimulationResult mc) {
        double z = (mc.meanRolls() - markov) / mc.stdErrorRolls();
        System.out.printf("markov=%.6f mc=%.4f se=%.4f z=%+.2f longest=%d%n",
                markov, mc.meanRolls(), mc.stdErrorRolls(), z, mc.longestRolls());
        assertEquals(0, mc.capped(), "no game should hit the fail-safe cap");
        assertTrue(Math.abs(z) <= 4, "Monte Carlo is " + z + " standard errors from Markov");
    }

    /** Proves the simulation agrees with the maths on the sourced board under overshoot wins. */
    @Test
    void sourcedBoardOvershootWins() {
        assertAgrees(BoardAnalysis.expectedRolls(Boards.dataGenetics(), FinishRule.OVERSHOOT_WINS, 6),
                simulate(Boards.dataGenetics(), FinishRule.OVERSHOOT_WINS, TurnRules.NONE, 1));
    }

    /** Proves the simulation agrees with the maths on a 30-square board with bounce back. */
    @Test
    void thirtySquareBoardBounceBack() {
        assertAgrees(BoardAnalysis.expectedRolls(Boards.thirtySquare(), FinishRule.BOUNCE_BACK, 6),
                simulate(Boards.thirtySquare(), FinishRule.BOUNCE_BACK, TurnRules.NONE, 2));
    }

    /** Proves an extra turn on six changes the number of turns but not the number of rolls. */
    @Test
    void anExtraTurnOnSixChangesTurnsButNotRolls() {
        // One player: an extra roll and the next turn's roll are the same transition, so rolls still follow Markov.
        SimulationResult mc = simulate(Boards.dataGenetics(), FinishRule.OVERSHOOT_WINS, new TurnRules(true, false), 3);

        assertAgrees(BoardAnalysis.expectedRolls(Boards.dataGenetics(), FinishRule.OVERSHOOT_WINS, 6), mc);
        assertTrue(mc.meanTurns() < mc.meanRolls());
    }

    /** Measures the three-sixes forfeit by simulation, since the maths model does not cover it. */
    @Test
    void threeSixesForfeitIsOutsideTheMarkovModelSoItIsMeasured() {
        SimulationResult mc = simulate(Boards.dataGenetics(), FinishRule.OVERSHOOT_WINS, new TurnRules(true, true), 4);
        double markov = BoardAnalysis.expectedRolls(Boards.dataGenetics(), FinishRule.OVERSHOOT_WINS, 6);

        System.out.printf("forfeit: mc rolls=%.4f se=%.4f turns=%.4f vs no-forfeit markov %.4f%n",
                mc.meanRolls(), mc.stdErrorRolls(), mc.meanTurns(), markov);
        assertEquals(0, mc.capped());
        assertTrue(mc.meanTurns() < mc.meanRolls());
        // Measured (seeded): a forfeited turn wastes its rolls and its progress; on this board that outweighs the snakes it undoes.
        assertTrue(mc.meanRolls() - markov > 4 * mc.stdErrorRolls(), "forfeit should lengthen the game on this board");
    }

    /** Proves a cap that stops every game gives an error, not a not-a-number result. */
    @Test
    void aCapThatStopsEveryGameIsAnErrorNotNaN() {
        // the shortest game is 7 turns, so a 1-turn cap finishes nothing
        assertThrows(IllegalArgumentException.class, () -> Simulation.run(Boards.dataGenetics(),
                FinishRule.OVERSHOOT_WINS, TurnRules.NONE, new FairDice(new SplittableRandom(1), 6), 100, 1));
    }
}
