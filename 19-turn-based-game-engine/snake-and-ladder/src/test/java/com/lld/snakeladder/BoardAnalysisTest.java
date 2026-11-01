package com.lld.snakeladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exact answers: BFS for the shortest game, an absorbing Markov chain for the mean, a pushed probability vector for the distribution. "Sourced" values are from DataGenetics (Berry, 2011); "computed" values are this code's output, recorded in the README.
 */
class BoardAnalysisTest {

    private static final Board DG = Boards.dataGenetics();

    /** Tests for the sourced 100-square board and a few small hand-checked boards. */
    @Nested
    class SourcedBoard {

        /** Proves that the shortest possible game is 7 rolls under each finish rule. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void shortestGameIsSevenRolls(FinishRule rule) {
            assertEquals(OptionalInt.of(7), BoardAnalysis.minRolls(DG, rule, 6)); // sourced
        }

        /** Proves the sourced 7-roll game plays out the same way when run through the real engine. */
        @Test
        void theSourcedSevenRollGamePlaysOutThroughTheEngine() {
            // 4 (->14), 6, 6, 2 (->84), 6, 6, 4 = 100: the article's example shortest game
            Game game = Game.builder(DG).players("solo").dice(new ScriptedDice(6, 4, 6, 6, 2, 6, 6, 4)).build();

            for (int i = 0; i < 7; i++) {
                game.takeTurn();
            }

            assertTrue(game.winner().isPresent());
        }

        /** Proves the expected number of rolls matches the sourced figure. */
        @Test
        void expectedRollsMatchTheSourcedFigure() {
            double expected = BoardAnalysis.expectedRolls(DG, FinishRule.OVERSHOOT_WINS, 6);

            assertEquals(36.2, expected, 0.05, "sourced, rounded to 1 dp");
            assertEquals(36.193070, expected, 1e-6, "computed");
        }

        /** Proves that stricter finish rules make the expected game longer. */
        @ParameterizedTest
        @CsvSource({"EXACT_OR_STAY, 39.598366", "BOUNCE_BACK, 43.739643"})
        void stricterFinishRulesMakeTheGameLonger(FinishRule rule, double computed) {
            assertEquals(computed, BoardAnalysis.expectedRolls(DG, rule, 6), 1e-6);
        }

        /** Proves the chance of finishing by each roll matches the sourced shape. */
        @Test
        void distributionMatchesTheSourcedShape() {
            double[] p = BoardAnalysis.finishProbabilities(DG, FinishRule.OVERSHOOT_WINS, 6, 2_000);

            int mode = 1;
            double total = 0;
            double mean = 0;
            double within100 = 0;
            for (int k = 1; k < p.length; k++) {
                mode = p[k] > p[mode] ? k : mode;
                total += p[k];
                mean += k * p[k];
                within100 += k <= 100 ? p[k] : 0;
            }

            assertEquals(20, mode, "sourced: most common game length");
            assertTrue(p[7] >= 0.0015 && p[7] < 0.0025, "sourced: ~2 in 1000 games take the minimum 7; computed " + p[7]);
            assertEquals(0.976, within100, 0.0005, "sourced: 97.6% of games end within 100 rolls");
            assertEquals(1.0, total, 1e-9);
            assertEquals(BoardAnalysis.expectedRolls(DG, FinishRule.OVERSHOOT_WINS, 6), mean, 1e-6,
                    "two independent exact methods agree");
        }

        /** Proves the maths has 81 resting squares to solve, under each finish rule. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void theMarkovChainHasEightyOneTransientStates(FinishRule rule) {
            // squares 0..99 minus the 19 jump starts: every one of them is reachable
            assertEquals(81, BoardAnalysis.restingSquares(DG, rule, 6));
        }

        /** Proves the median is 30 and not the 29 the article reports, using the exact figures for the chance of finishing by roll 29 and by roll 30. */
        @Test
        void medianIsThirtyNotTheSourcedTwentyNine() {
            // Finding: the article reports a median of 29. The exact CDF crosses 0.5 at 30: P(game <= 29) = 0.4975, P(game <= 30) = 0.5185. See README section 3.
            double[] p = BoardAnalysis.finishProbabilities(DG, FinishRule.OVERSHOOT_WINS, 6, 30);
            double cdf = 0;
            for (int k = 1; k <= 29; k++) {
                cdf += p[k];
            }

            assertEquals(0.497462, cdf, 1e-6);
            assertEquals(0.518490, cdf + p[30], 1e-6);
        }
    }

    /** Small boards where the answer can be worked out by hand. */
    @Nested
    class HandCheckedBoards {

        @ParameterizedTest(name = "{0} squares, d{1}, {2}: {3}")
        @CsvSource({
                "2, 2, OVERSHOOT_WINS, 1.5",   // 1 or 2; from 1 any roll wins
                "2, 2, EXACT_OR_STAY,  2.0",   // from 1 only a 1 wins: 1 + 1/2 * 2
                "2, 2, BOUNCE_BACK,    2.0",   // faces == last is allowed: from 1 a 2 bounces back to 1
                "3, 2, OVERSHOOT_WINS, 2.25",
                "3, 2, EXACT_OR_STAY,  3.0",
                "3, 2, BOUNCE_BACK,    3.0"})  // from 2 a 2 bounces back to 2, same as staying
        /** Proves the computed expected rolls match the hand-worked answer on tiny boards. */
        void expectedRollsOnTinyBoards(int last, int faces, FinishRule rule, double expected) {
            assertEquals(expected, BoardAnalysis.expectedRolls(Board.builder(last).build(), rule, faces), 1e-12);
        }

        /** Proves that bounce back is refused when the die has more faces than the board has squares. */
        @Test
        void bounceBackRejectsADieBiggerThanTheBoard() {
            // 2 squares, d3: from 1 a 3 would bounce to 0, off the board
            assertThrows(IllegalArgumentException.class,
                    () -> BoardAnalysis.expectedRolls(Board.builder(2).build(), FinishRule.BOUNCE_BACK, 3));
        }

        /** Proves the expected rolls on a 30-square board match the computed figure. */
        @ParameterizedTest
        @CsvSource({"OVERSHOOT_WINS, 9.499126", "EXACT_OR_STAY, 14.417600", "BOUNCE_BACK, 19.927070"})
        void thirtySquareBoard(FinishRule rule, double computed) {
            Board board = Boards.thirtySquare();

            assertEquals(OptionalInt.of(3), BoardAnalysis.minRolls(board, rule, 6));
            assertEquals(computed, BoardAnalysis.expectedRolls(board, rule, 6), 1e-6);
        }
    }

    /** Boards where some games can never end. */
    @Nested
    class UnfinishableBoards {

        /** Proves a shortest game can exist even when half of all games never end, so the shortest game alone does not show a board is playable. */
        @Test
        void aShortestGameCanExistWhileHalfOfAllGamesNeverEnd() {
            Board trap = TestBoards.TRAP;

            assertEquals(OptionalInt.of(2), BoardAnalysis.minRolls(trap, FinishRule.OVERSHOOT_WINS, 2));
            assertEquals(OptionalInt.of(2), BoardAnalysis.stuckSquare(trap, FinishRule.OVERSHOOT_WINS, 2));
            assertEquals(Double.POSITIVE_INFINITY, BoardAnalysis.expectedRolls(trap, FinishRule.OVERSHOOT_WINS, 2));
        }

        /** Proves that a wall of snakes makes the end unreachable and the game is refused. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void aWallOfSnakesMakesTheFinishUnreachable(FinishRule rule) {
            Board walled = TestBoards.walled();

            assertEquals(OptionalInt.empty(), BoardAnalysis.minRolls(walled, rule, 6));
            assertEquals(OptionalInt.of(0), BoardAnalysis.stuckSquare(walled, rule, 6));
            assertEquals(Double.POSITIVE_INFINITY, BoardAnalysis.expectedRolls(walled, rule, 6));
        }

        /** Proves the sourced board has no trap, so it is accepted. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void theSourcedBoardHasNoTrap(FinishRule rule) {
            assertEquals(OptionalInt.empty(), BoardAnalysis.stuckSquare(DG, rule, 6));
            assertEquals(OptionalInt.empty(), BoardAnalysis.stuckSquare(DG, rule, new TurnRules(true, true), 6));
        }

        /** Proves that only the three-sixes rule turns a wall of sixes into a trap. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void onlyTheForfeitTurnsAWallOfSixesIntoATrap(FinishRule rule) {
            Board wall = TestBoards.sixesWall();

            assertEquals(OptionalInt.of(6), BoardAnalysis.minRolls(wall, rule, 6), "any roll -> 1, then 6, 6, 6 -> 19, then 6, 5");
            assertEquals(OptionalInt.empty(), BoardAnalysis.stuckSquare(wall, rule, TurnRules.NONE, 6));
            assertEquals(OptionalInt.empty(), BoardAnalysis.stuckSquare(wall, rule, new TurnRules(true, false), 6));
            assertEquals(OptionalInt.of(0), BoardAnalysis.stuckSquare(wall, rule, new TurnRules(true, true), 6));
        }
    }
}
