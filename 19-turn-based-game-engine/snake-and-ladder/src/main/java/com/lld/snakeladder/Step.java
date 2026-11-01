package com.lld.snakeladder;

/**
 * One roll inside a turn.
 *
 * @param roll   the face rolled
 * @param from   where the token was before the roll
 * @param landed where the finish rule put it, before any jump
 * @param to     where it ended up after the jump (equal to {@code landed} if there was none)
 */
public record Step(int roll, int from, int landed, int to) {

    /** True if a ladder or snake moved the token on this roll. */
    public boolean jumped() {
        return to != landed;
    }
}
