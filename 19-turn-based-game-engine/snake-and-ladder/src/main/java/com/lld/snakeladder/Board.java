package com.lld.snakeladder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Squares {@code 1..lastSquare} plus the jumps between them. Immutable once built.
 *
 * <p>Tokens start on square {@code 0}, off the board. That is why a ladder may start on square 1 (the DataGenetics board has 1→38): you can only take it by rolling a 1 on your first roll.
 *
 * <p>Validation, all at build time with a precise message:
 * <ul>
 *   <li>{@code lastSquare >= 2}; every jump inside {@code 1..lastSquare}; ladders go up, snakes down;</li>
 *   <li>no jump starts on the last square (you'd win and lose on the same move);</li>
 *   <li>at most one jump per start square;</li>
 *   <li><b>no chains</b>: a jump may not end on another jump's start. Chains are the only way to get a jump cycle (a 5→9 ladder plus a 9→5 snake loops forever), so forbidding them makes cycles impossible by construction and every jump resolves in one hop.</li>
 * </ul>
 * <p>Whether a board can be <em>finished</em> depends on the dice and the finish rule, so that check lives in {@link Game.Builder#build()} (see {@link BoardAnalysis#stuckSquare}).
 */
public final class Board {

    private final int lastSquare;
    private final Map<Integer, Jump> jumpsByStart;

    /** Creates the board. Used only by the builder, so every board has been checked. */
    private Board(int lastSquare, Map<Integer, Jump> jumpsByStart) {
        this.lastSquare = lastSquare;
        this.jumpsByStart = Map.copyOf(jumpsByStart);
    }

    /** Starts a board builder for the given number of squares. */
    public static Builder builder(int lastSquare) {
        return new Builder(lastSquare);
    }

    /** How many squares the board has. The last square is the winning one. */
    public int lastSquare() {
        return lastSquare;
    }

    /** Where a token that lands on {@code square} ends up: the jump's end, or the square itself. */
    public int destination(int square) {
        if (square < 0 || square > lastSquare) {
            throw new IllegalArgumentException("square " + square + " is outside 0.." + lastSquare);
        }
        Jump jump = jumpsByStart.get(square);
        return jump == null ? square : jump.to();
    }

    /** All jumps, ordered by start square. */
    public List<Jump> jumps() {
        return jumpsByStart.values().stream().sorted(Comparator.comparingInt(Jump::from)).toList();
    }

    /** Collects ladders and snakes, then checks them all in {@link #build()}. */
    public static final class Builder {

        private final int lastSquare;
        private final List<Jump> jumps = new ArrayList<>();

        /** Starts a builder. Throws if the board has fewer than 2 squares. */
        private Builder(int lastSquare) {
            if (lastSquare < 2) {
                throw new IllegalArgumentException("lastSquare must be >= 2: " + lastSquare);
            }
            this.lastSquare = lastSquare;
        }

        /** Adds a ladder (it must go up). Throws if it goes down or leaves the board. */
        public Builder ladder(int from, int to) {
            if (to <= from) {
                throw new IllegalArgumentException("a ladder must go up: " + from + "->" + to);
            }
            return add(new Jump(from, to));
        }

        /** Adds a snake (it must go down). Throws if it goes up or leaves the board. */
        public Builder snake(int from, int to) {
            if (to >= from) {
                throw new IllegalArgumentException("a snake must go down: " + from + "->" + to);
            }
            return add(new Jump(from, to));
        }

        /** Shared checks for ladders and snakes: both ends must be on the board and no jump may start on the last square. */
        private Builder add(Jump jump) {
            for (int square : new int[] {jump.from(), jump.to()}) {
                if (square < 1 || square > lastSquare) {
                    throw new IllegalArgumentException(
                            "jump " + jump.from() + "->" + jump.to() + " leaves the board 1.." + lastSquare);
                }
            }
            if (jump.from() == lastSquare) {
                throw new IllegalArgumentException("no jump may start on the last square " + lastSquare);
            }
            jumps.add(jump);
            return this;
        }

        /** Checks the whole board and builds it. Throws if two jumps start on the same square or if jumps are chained. */
        public Board build() {
            Map<Integer, Jump> byStart = new HashMap<>();
            for (Jump jump : jumps) {
                Jump clash = byStart.putIfAbsent(jump.from(), jump);
                if (clash != null) {
                    throw new IllegalArgumentException("two jumps start on square " + jump.from()
                            + ": " + clash.from() + "->" + clash.to() + " and " + jump.from() + "->" + jump.to());
                }
            }
            for (Jump jump : jumps) {
                Jump next = byStart.get(jump.to());
                if (next != null) {
                    throw new IllegalArgumentException("chained jumps are not allowed: " + jump.from() + "->"
                            + jump.to() + " ends where " + next.from() + "->" + next.to() + " starts");
                }
            }
            return new Board(lastSquare, byStart);
        }
    }
}
