package com.lld.leaderboard;

/**
 * One row of a leaderboard as seen by a reader.
 *
 * @param rank           the 1-based position in the board (1 is the top). Positions are unique, because ties are broken by time and then by user id.
 * @param user           the user id.
 * @param score          the user's current score.
 * @param reachedAtMillis the wall-clock time (epoch millis) when the user got this exact score. It is the tie-breaker: the earlier time ranks higher.
 */
public record Ranked(int rank, String user, long score, long reachedAtMillis) {
}
