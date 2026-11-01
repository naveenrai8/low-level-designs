package com.lld.tictactoe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Both incremental detectors against a brute-force full-board scan, over 10,000 seeded random games of assorted (N, K, P), with random undos mixed in.
 *
 * <p>Brute force is valid as an oracle because the test keeps "no line exists yet" true: a win is always undone straight away. So any line on the board must run through the stone just placed.
 */
class DifferentialWinTest {

    private static final int GAMES = 10_000;
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    /** Proves the two fast win detectors always agree with a slow brute-force check, on random games across many sizes, win lengths and player counts, including after undo. */
    @Test
    void incrementalDetectorsAgreeWithBruteForce() {
        SplittableRandom random = new SplittableRandom(19);
        long checks = 0;
        long wins = 0;
        for (int g = 0; g < GAMES; g++) {
            int n = 1 + random.nextInt(6);
            int k = 1 + random.nextInt(n);
            int players = 2 + random.nextInt(3);

            int[][] owner = new int[n][n];
            for (int[] row : owner) {
                Arrays.fill(row, -1);
            }
            DirectionalScan scan = new DirectionalScan(n, k);
            LineCounters counters = k == n ? new LineCounters(n, players) : null;
            Deque<int[]> placed = new ArrayDeque<>(); // {row, col, player}
            int turn = 0;

            for (int step = 0; step < 3 * n * n; step++) {
                if (!placed.isEmpty() && random.nextInt(6) == 0) {
                    int[] last = placed.pop();
                    undo(owner, scan, counters, last);
                    turn = last[2];
                    continue;
                }
                List<int[]> empty = emptyCells(owner);
                if (empty.isEmpty()) {
                    break;
                }
                int[] cell = empty.get(random.nextInt(empty.size()));
                int mover = turn;
                int r = cell[0];
                int c = cell[1];
                owner[r][c] = turn;
                scan.place(r, c, turn);
                if (counters != null) {
                    counters.place(r, c, turn);
                }
                placed.push(new int[] {r, c, turn});

                boolean expected = bruteForceHasLine(owner, k, mover);
                Optional<List<Cell>> scanLine = scan.winningLine(r, c, turn);
                assertEquals(expected, scanLine.isPresent(), () -> describe(n, k, players, owner));
                scanLine.ifPresent(line -> assertIsValidLine(line, owner, k, mover, r, c));
                if (counters != null) {
                    Optional<List<Cell>> counterLine = counters.winningLine(r, c, turn);
                    assertEquals(expected, counterLine.isPresent(), () -> describe(n, k, players, owner));
                    counterLine.ifPresent(line -> assertIsValidLine(line, owner, k, mover, r, c));
                }
                checks++;
                if (expected) {
                    wins++;
                    undo(owner, scan, counters, placed.pop()); // keep "no line yet" true
                } else {
                    turn = (turn + 1) % players;
                }
            }
        }
        assertTrue(checks > 100_000, "checks=" + checks);
        assertTrue(wins > 10_000, "wins=" + wins); // the win branch is exercised, not just the miss
    }

    /** Takes back one move in all three views of the board (the plain grid and both detectors) so they stay in step. */
    private static void undo(int[][] owner, DirectionalScan scan, LineCounters counters, int[] move) {
        owner[move[0]][move[1]] = -1;
        scan.remove(move[0], move[1], move[2]);
        if (counters != null) {
            counters.remove(move[0], move[1], move[2]);
        }
    }

    /** The slow, obviously correct check: does this player have K in a row anywhere on the board? Used only to judge the fast detectors. */
    private static boolean bruteForceHasLine(int[][] owner, int k, int player) {
        int n = owner.length;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                for (int[] d : DIRECTIONS) {
                    int i = 0;
                    while (i < k && inside(n, r + i * d[0], c + i * d[1]) && owner[r + i * d[0]][c + i * d[1]] == player) {
                        i++;
                    }
                    if (i == k) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** At least K cells, all the player's, contiguous in one direction, and through (r, c). */
    private static void assertIsValidLine(List<Cell> line, int[][] owner, int k, int player, int r, int c) {
        assertTrue(line.size() >= k, "line too short: " + line);
        assertTrue(line.contains(new Cell(r, c)), "line misses the last move: " + line);
        for (Cell cell : line) {
            assertEquals(player, owner[cell.row()][cell.col()], "line has a foreign cell: " + line);
        }
        if (line.size() > 1) {
            int dr = line.get(1).row() - line.get(0).row();
            int dc = line.get(1).col() - line.get(0).col();
            for (int i = 1; i < line.size(); i++) {
                assertEquals(new Cell(line.get(i - 1).row() + dr, line.get(i - 1).col() + dc), line.get(i));
            }
        }
    }

    /** Lists every empty cell on the plain grid. */
    private static List<int[]> emptyCells(int[][] owner) {
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < owner.length; r++) {
            for (int c = 0; c < owner.length; c++) {
                if (owner[r][c] == -1) {
                    cells.add(new int[] {r, c});
                }
            }
        }
        return cells;
    }

    /** True when (r, c) is on an n-by-n board. */
    private static boolean inside(int n, int r, int c) {
        return r >= 0 && r < n && c >= 0 && c < n;
    }

    /** Turns a board into text, so a failing assertion shows exactly which position went wrong. */
    private static String describe(int n, int k, int players, int[][] owner) {
        return "N=" + n + " K=" + k + " P=" + players + " board=" + Arrays.deepToString(owner);
    }
}
