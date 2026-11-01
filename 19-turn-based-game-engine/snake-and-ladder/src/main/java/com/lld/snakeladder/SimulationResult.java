package com.lld.snakeladder;

/**
 * Summary of many single-player games.
 *
 * @param meanRolls     mean dice rolls per finished game
 * @param stdErrorRolls standard error of {@code meanRolls}: sample standard deviation / √(finished games)
 * @param meanTurns     mean turns per finished game (fewer than rolls when a six earns an extra roll)
 * @param longestRolls  the longest finished game, in rolls
 * @param capped        games stopped at the turn cap without finishing (excluded from the means)
 */
public record SimulationResult(int games, double meanRolls, double stdErrorRolls, double meanTurns,
                               int longestRolls, int capped) {
}
