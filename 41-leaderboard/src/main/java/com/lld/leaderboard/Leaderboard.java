package com.lld.leaderboard;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A live ranking of users by score.
 *
 * <p><b>Order (the tie rule).</b> Higher score first. If scores are equal, the user who reached that score earlier comes first. If that is also equal, the smaller user id comes first. Because of the last rule the order is total, so two users never share a position.
 *
 * <p><b>Rank.</b> {@link #rank(String)} is the 1-based position in that total order. {@link #competitionRank(String)} is the "1224" style rank where users with the same score share a number and the next number is skipped. "Dense" ranking (1223) is not offered, see the README for why.
 *
 * <p><b>Errors.</b> A null or blank user id throws {@link IllegalArgumentException}. A negative {@code k} or a bad range throws {@link IllegalArgumentException}. An {@code increment} that would overflow a {@code long} throws {@link ArithmeticException} and changes nothing. Asking about an unknown user is not an error, it returns an empty optional.
 */
public interface Leaderboard {

    /** Sets the user's score, adding the user if needed. If the score is unchanged nothing moves, and the user keeps the original reach time. Otherwise the reach time becomes "now". */
    void setScore(String user, long score);

    /** Adds {@code delta} (may be negative) to the user's score, starting from 0 for a new user, and returns the new score. The read and the write happen as one step. */
    long increment(String user, long delta);

    /** Removes the user. Returns true if they were on the board, false if not. */
    boolean remove(String user);

    /** Returns the user's score, or empty if the user is not on the board. */
    OptionalLong score(String user);

    /** Returns the 1-based position of the user (ties already broken), or empty if the user is not on the board. */
    OptionalInt rank(String user);

    /** Returns the "1224" rank: 1 plus the number of users with a strictly higher score. Empty if the user is not on the board. */
    OptionalInt competitionRank(String user);

    /** Returns the best {@code k} rows in order. If fewer than {@code k} users exist, returns them all. */
    List<Ranked> top(int k);

    /** Returns rows from position {@code fromRank} to {@code toRank}, both 1-based and inclusive. The range is cut to the board size, so asking past the end returns fewer rows (or none). */
    List<Ranked> range(int fromRank, int toRank);

    /** Returns how many users are on the board. */
    int size();
}
