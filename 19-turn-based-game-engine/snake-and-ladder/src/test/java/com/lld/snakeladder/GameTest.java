package com.lld.snakeladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Turn mechanics, driven by scripted dice so every roll is chosen by the test. */
class GameTest {

    private static final Board SMALL = TestBoards.SMALL;

    /** Test helper: a one-player game with a fixed list of rolls on a 10-square board. */
    private static Game solo(FinishRule rule, TurnRules turnRules, int... rolls) {
        return Game.builder(SMALL).players("solo").dice(new ScriptedDice(6, rolls))
                .finishRule(rule).turnRules(turnRules).build();
    }

    /** Puts the solo player on square 18 (6, 6, 6 under TurnRules.NONE), then plays {@code then}. */
    private static Game onEighteen(FinishRule rule, int then) {
        Game game = solo(rule, TurnRules.NONE, 6, 6, 6, then);
        for (int i = 0; i < 3; i++) {
            game.takeTurn();
        }
        assertEquals(18, game.positions().get(new Player("solo")));
        return game;
    }

    /** Tests for ladders, snakes and the order of turns. */
    @Nested
    class Movement {

        /** Proves a ladder moves the token up and a snake moves it down. */
        @Test
        void ladderClimbsAndSnakeSlides() {
            Game game = solo(FinishRule.OVERSHOOT_WINS, TurnRules.NONE, 3, 6);

            Step climb = game.takeTurn().steps().getFirst();
            Step slide = game.takeTurn().steps().getFirst();

            assertEquals(new Step(3, 0, 3, 11), climb);
            assertEquals(new Step(6, 11, 17, 5), slide);
            assertTrue(climb.jumped() && slide.jumped());
        }

        /** Proves players take turns in order and may share a square. */
        @Test
        void playersRotateInOrderAndShareSquaresFreely() {
            Game game = Game.builder(SMALL).players("a", "b", "c")
                    .dice(new ScriptedDice(6, 1, 1, 3, 4)).build();

            List<String> movers = List.of(game.takeTurn(), game.takeTurn(), game.takeTurn(), game.takeTurn())
                    .stream().map(t -> t.player().name()).toList();

            assertEquals(List.of("a", "b", "c", "a"), movers);
            assertEquals(Map.of(new Player("a"), 5, new Player("b"), 1, new Player("c"), 11), game.positions());
            assertEquals("b", game.currentPlayer().name());
        }

        /** Proves the positions map is a snapshot that does not follow later moves. */
        @Test
        void positionsIsASnapshot() {
            Game game = solo(FinishRule.OVERSHOOT_WINS, TurnRules.NONE, 2);
            Map<Player, Integer> before = game.positions();

            game.takeTurn();

            assertEquals(0, before.get(new Player("solo")));
        }
    }

    /** Tests for what happens at the end of the board under each rule. */
    @Nested
    class FinishRules {

        /** Proves that overshoot wins: a big roll at the end wins the game. */
        @Test
        void overshootWins() {
            TurnResult turn = onEighteen(FinishRule.OVERSHOOT_WINS, 5).takeTurn();

            assertTrue(turn.won());
            assertEquals(new Step(5, 18, 20, 20), turn.steps().getFirst());
        }

        /** Proves that under exact-or-stay a too-big roll is lost, the token stays and no jump applies. */
        @Test
        void exactOrStayLosesTheOvershootingRollAndAppliesNoJump() {
            Game game = onEighteen(FinishRule.EXACT_OR_STAY, 5);

            TurnResult turn = game.takeTurn();

            assertFalse(turn.won());
            assertEquals(new Step(5, 18, 18, 18), turn.steps().getFirst());
        }

        /** Proves that an exact roll wins under every finish rule. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void anExactRollWinsUnderEveryRule(FinishRule rule) {
            assertTrue(onEighteen(rule, 2).takeTurn().won());
        }

        /** Proves bounce back walks the extra steps back and applies any jump found there. */
        @Test
        void bounceBackWalksTheExcessBackAndAppliesTheJumpThere() {
            // 18 + 5 = 23, 3 past 20 -> bounces to 17, which is a snake head -> 5
            TurnResult turn = onEighteen(FinishRule.BOUNCE_BACK, 5).takeTurn();

            assertEquals(new Step(5, 18, 17, 5), turn.steps().getFirst());
            assertEquals(5, turn.end());
        }
    }

    /** Tests for the six rules: extra roll and three-sixes forfeit. */
    @Nested
    class SixRules {

        private final TurnRules extra = new TurnRules(true, false);
        private final TurnRules forfeit = new TurnRules(true, true);

        /** Proves a six gives the same player another roll in the same turn. */
        @Test
        void aSixEarnsAnotherRollInTheSameTurn() {
            Game game = Game.builder(SMALL).players("a", "b")
                    .dice(new ScriptedDice(6, 6, 2, 1)).turnRules(extra).build();

            TurnResult first = game.takeTurn();

            assertEquals(List.of(new Step(6, 0, 6, 6), new Step(2, 6, 8, 8)), first.steps());
            assertEquals("b", game.takeTurn().player().name());
        }

        /** Proves that without the forfeit rule, three sixes in a row are just kept rolled. */
        @Test
        void withoutForfeitThreeSixesJustKeepRolling() {
            TurnResult turn = Game.builder(Board.builder(100).build()).players("a")
                    .dice(new ScriptedDice(6, 6, 6, 6, 1)).turnRules(extra).build().takeTurn();

            assertEquals(4, turn.steps().size());
            assertEquals(19, turn.end());
        }

        /** Proves three sixes cancel the whole turn and put the token back where the turn began. */
        @Test
        void threeSixesCancelTheWholeTurnAndReturnToItsStartSquare() {
            Board open = Board.builder(100).build();
            Game game = Game.builder(open).players("a", "b")
                    .dice(new ScriptedDice(6, 4, 1, 6, 6, 6)).turnRules(forfeit).build();
            game.takeTurn(); // a -> 4
            game.takeTurn(); // b -> 1

            TurnResult turn = game.takeTurn(); // a: 6 (->10), 6 (->16), 6 -> forfeit

            assertTrue(turn.forfeited());
            assertEquals(4, turn.start());
            assertEquals(4, turn.end());
            assertEquals(new Step(6, 16, 16, 16), turn.steps().getLast(), "the forfeiting roll moves nothing");
            assertEquals(4, game.positions().get(new Player("a")));
            assertEquals("b", game.currentPlayer().name());
        }

        /** Proves that winning on an extra roll ends the turn and the game. */
        @Test
        void winningOnAnExtraRollEndsTheTurnAndTheGame() {
            // 14 squares, no jumps: 6, 6, 2 is one turn under the extra-turn rule, and it wins
            Game game = Game.builder(Board.builder(14).build()).players("a", "b")
                    .dice(new ScriptedDice(6, 6, 6, 2, 1)).turnRules(extra).build();

            TurnResult turn = game.takeTurn();

            assertTrue(turn.won());
            assertEquals(3, turn.steps().size());
            assertEquals(Optional.of(new Player("a")), game.winner());
            assertEquals("a", game.currentPlayer().name(), "after a win the winner stays current");
            IllegalStateException over = assertThrows(IllegalStateException.class, game::takeTurn);
            assertTrue(over.getMessage().contains("game over"), "b never rolls; the script's 1 is unused");
        }

        /** Proves that winning on the first six ends the game without another roll. */
        @Test
        void winOnTheFirstSixEndsTheGameWithoutAnExtraRoll() {
            Game game = Game.builder(Board.builder(6).build()).players("a", "b")
                    .dice(new ScriptedDice(6, 6)).turnRules(forfeit).build();

            TurnResult turn = game.takeTurn();

            assertTrue(turn.won());
            assertEquals(1, turn.steps().size(), "the script has one roll; an extra roll would exhaust it");
        }

        /** Proves that winning on the second six stops the turn before a forfeit could happen. */
        @Test
        void winOnTheSecondSixStopsBeforeAForfeitCanHappen() {
            Game game = Game.builder(Board.builder(12).build()).players("a", "b")
                    .dice(new ScriptedDice(6, 6, 6, 6)).turnRules(forfeit).build();

            TurnResult turn = game.takeTurn();

            assertTrue(turn.won());
            assertFalse(turn.forfeited());
            assertEquals(2, turn.steps().size(), "the second six wins before a third can be rolled");
        }
    }

    /** Tests that bad setups are refused when the game is built. */
    @Nested
    class Validation {

        /** Proves no players, or two players with the same name, are refused. */
        @Test
        void rejectsBadPlayerLists() {
            assertThrows(IllegalArgumentException.class,
                    () -> Game.builder(SMALL).dice(new ScriptedDice(6)).build());
            assertThrows(IllegalArgumentException.class,
                    () -> Game.builder(SMALL).players("a", "a").dice(new ScriptedDice(6)).build());
            assertThrows(IllegalArgumentException.class, () -> Game.builder(SMALL).players(" "));
        }

        /** Proves dice are required, so a game never has hidden randomness. */
        @Test
        void diceAreRequiredSoThereIsNoHiddenRandomness() {
            assertThrows(NullPointerException.class, () -> Game.builder(SMALL).players("a").build());
        }

        /** Proves the forfeit rule is refused without extra turns. */
        @Test
        void forfeitWithoutExtraTurnsIsMeaningless() {
            assertThrows(IllegalArgumentException.class, () -> new TurnRules(false, true));
        }

        /** Proves bounce back is refused when the die has more faces than squares. */
        @Test
        void bounceBackNeedsNoMoreFacesThanSquares() {
            // 5 squares, d6: from 4 a 6 bounces to 0, off the board. With a d5 (or d6 on 6 squares) the bounce stays >= 1
            Board tiny = Board.builder(5).build();
            assertTrue(Game.builder(tiny).players("a").dice(new ScriptedDice(5))
                    .finishRule(FinishRule.BOUNCE_BACK).build().winner().isEmpty());

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Game.builder(tiny).players("a").dice(new ScriptedDice(6))
                            .finishRule(FinishRule.BOUNCE_BACK).build());
            assertTrue(e.getMessage().contains("BOUNCE_BACK"));
        }

        /** Proves one builder can start independent games and re-checks the board after a rule change. */
        @Test
        void aReusedBuilderStartsIndependentGamesAndRechecksAfterARuleChange() {
            Game.Builder setup = Game.builder(Board.builder(5).build()).players("a").dice(new ScriptedDice(6, 6, 6));
            Game first = setup.build();
            Game second = setup.build();

            first.takeTurn();

            assertTrue(first.winner().isPresent());
            assertTrue(second.winner().isEmpty(), "games share the dice, but not positions or the winner");
            assertThrows(IllegalArgumentException.class, () -> setup.finishRule(FinishRule.BOUNCE_BACK).build(),
                    "the memoised board check must not survive a rule change");
        }

        /** Proves that changing the dice makes the builder check the board again. */
        @Test
        void aDiceChangeRechecksTheBoard() {
            Game.Builder setup = Game.builder(Board.builder(5).build()).players("a")
                    .finishRule(FinishRule.BOUNCE_BACK).dice(new ScriptedDice(2));
            setup.build();

            assertThrows(IllegalArgumentException.class, () -> setup.dice(new ScriptedDice(6)).build(),
                    "a d6 can bounce off a 5-square board; the memo must not hide that");
        }

        /** Proves the forfeit rule can make a board impossible to finish, and that this is caught. */
        @Test
        void theThreeSixesForfeitCanMakeABoardUnfinishable() {
            Game.Builder setup = Game.builder(TestBoards.sixesWall()).players("a").dice(new ScriptedDice(6));
            setup.build();
            setup.turnRules(new TurnRules(true, false)).build();

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> setup.turnRules(new TurnRules(true, true)).build());
            assertTrue(e.getMessage().contains("nobody can ever reach square 30"), e.getMessage());
        }

        /** Proves a board nobody can finish is refused under every finish rule. */
        @ParameterizedTest
        @EnumSource(FinishRule.class)
        void rejectsABoardNobodyCanFinish(FinishRule rule) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Game.builder(TestBoards.walled()).players("a").dice(new ScriptedDice(6))
                            .finishRule(rule).build());
            assertTrue(e.getMessage().contains("nobody can ever reach square 100"), e.getMessage());
        }

        /** Proves a board that traps anyone who misses the ladder is refused. */
        @Test
        void rejectsABoardThatTrapsAnyoneWhoMissesTheLadder() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Game.builder(TestBoards.TRAP).players("a").dice(new ScriptedDice(2)).build());
            assertTrue(e.getMessage().contains("stuck forever on square 2"), e.getMessage());
        }

        /** Proves a die that returns a bad number makes the game fail loudly. */
        @Test
        void diceThatBreakTheirContractFailLoudly() {
            Dice broken = new Dice() {
                /** A broken die that returns 7 on a 6-faced die. */
                @Override
                public int roll() {
                    return 7;
                }

                /** Says it has 6 faces, which the roll above breaks. */
                @Override
                public int faces() {
                    return 6;
                }
            };
            Game game = Game.builder(SMALL).players("a").dice(broken).build();

            assertThrows(IllegalStateException.class, game::takeTurn);
        }

        /** Proves a scripted die refuses a script with a roll that is not on the die. */
        @Test
        void scriptedDiceValidateTheirScript() {
            assertThrows(IllegalArgumentException.class, () -> new ScriptedDice(6, 7));
            assertThrows(IllegalArgumentException.class, () -> new ScriptedDice(6, 0));
            ScriptedDice once = new ScriptedDice(6, 3);
            once.roll();
            assertThrows(IllegalStateException.class, once::roll);
        }
    }
}
