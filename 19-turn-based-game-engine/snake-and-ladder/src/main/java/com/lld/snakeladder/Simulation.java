package com.lld.snakeladder;

/**
 * Monte Carlo through the real {@link Game}, one player per game. That makes it a cross-check of the game loop against {@link BoardAnalysis}, not a second copy of the same formula.
 */
public final class Simulation {

    /** Utility class, so it cannot be created. */
    private Simulation() {
    }

    /**
     * @param maxTurnsPerGame fail-safe. A game can in principle run forever (snakes loop you back), so a simulation without a cap can hang. Capped games are counted, not hidden.
     * @throws IllegalArgumentException if fewer than 2 games finish within the cap
     */
    public static SimulationResult run(Board board, FinishRule rule, TurnRules turnRules, Dice dice,
                                       int games, int maxTurnsPerGame) {
        if (games < 2 || maxTurnsPerGame < 1) {
            throw new IllegalArgumentException("need games >= 2 and maxTurnsPerGame >= 1");
        }
        long sumRolls = 0;
        long sumRollsSquared = 0;
        long sumTurns = 0;
        int longest = 0;
        int capped = 0;
        Game.Builder setup = Game.builder(board).players("solo").dice(dice).finishRule(rule).turnRules(turnRules);
        for (int g = 0; g < games; g++) {
            Game game = setup.build();
            int rolls = 0;
            int turns = 0;
            while (game.winner().isEmpty() && turns < maxTurnsPerGame) {
                rolls += game.takeTurn().steps().size();
                turns++;
            }
            if (game.winner().isEmpty()) {
                capped++;
                continue;
            }
            sumRolls += rolls;
            sumRollsSquared += (long) rolls * rolls;
            sumTurns += turns;
            longest = Math.max(longest, rolls);
        }
        int finished = games - capped;
        if (finished < 2) {
            throw new IllegalArgumentException(finished + " of " + games + " games finished within "
                    + maxTurnsPerGame + " turns; need 2 for a mean and a standard error");
        }
        double mean = (double) sumRolls / finished;
        double variance = (sumRollsSquared - finished * mean * mean) / (finished - 1);
        return new SimulationResult(games, mean, Math.sqrt(variance / finished),
                (double) sumTurns / finished, longest, capped);
    }
}
