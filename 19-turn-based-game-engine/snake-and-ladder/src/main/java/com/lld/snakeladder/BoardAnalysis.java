package com.lld.snakeladder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntConsumer;

/**
 * Questions about a board that need no simulation. They model <b>one player</b>, and, except {@link #stuckSquare(Board, FinishRule, TurnRules, int)}, {@link TurnRules#NONE}: one roll per turn, so turns equal rolls.
 *
 * <p>The state is the square a token rests on. A token never rests on a jump start, and "next square" depends only on the current square and the roll, so the game is a Markov chain whose one absorbing state is the last square.
 *
 * <p>The six rules break this: whether a six forfeits depends on how many sixes came before in the turn. Modelling them means expanding the state to (square, sixes so far this turn). They are measured by {@link Simulation} instead.
 */
public final class BoardAnalysis {

    /** Utility class, so it cannot be created. */
    private BoardAnalysis() {
    }

    /** Fewest rolls from the start (square 0) to the last square, by BFS. Empty if it can't be reached. O(squares × faces). */
    public static OptionalInt minRolls(Board board, FinishRule rule, int faces) {
        checkDice(board, rule, faces);
        int last = board.lastSquare();
        int[] dist = new int[last + 1];
        Arrays.fill(dist, -1);
        dist[0] = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(0);
        while (!queue.isEmpty()) {
            int square = queue.poll();
            if (square == last) {
                return OptionalInt.of(dist[last]);
            }
            for (int roll = 1; roll <= faces; roll++) {
                int next = next(board, rule, square, roll);
                if (dist[next] < 0) {
                    dist[next] = dist[square] + 1;
                    queue.add(next);
                }
            }
        }
        return OptionalInt.empty();
    }

    /** {@link #stuckSquare(Board, FinishRule, TurnRules, int)} under {@link TurnRules#NONE}. */
    public static OptionalInt stuckSquare(Board board, FinishRule rule, int faces) {
        return stuckSquare(board, rule, TurnRules.NONE, faces);
    }

    /**
     * The lowest square a token can reach from the start but never leave for the finish. A game on such a board may never end, with positive probability. {@code 0} means the finish is unreachable outright. Empty means every reachable square can still finish, so a game ends with probability 1.
     *
     * <p>A minimum roll count existing is <em>not</em> enough. A ladder can skip a wall of snakes that traps everyone who missed it.
     *
     * <p>An extra roll on a six changes nothing here: the squares visited roll by roll are the same. The three-sixes forfeit does change it, because whether a roll counts depends on the rolls before it in the turn. So with the forfeit on, one step of the search is a <em>whole turn</em> from a turn-start square (at most F³ roll sequences), and a board that needs three sixes in a row to cross a wall is correctly reported stuck.
     */
    public static OptionalInt stuckSquare(Board board, FinishRule rule, TurnRules turnRules, int faces) {
        checkDice(board, rule, faces);
        int last = board.lastSquare();
        Successors successors = turnRules.threeSixesForfeit()
                ? (square, out) -> turnOutcomes(board, rule, faces, square, out)
                : (square, out) -> rollOutcomes(board, rule, faces, square, out);
        boolean[] reachable = reachable(last, successors);
        List<List<Integer>> predecessors = new ArrayList<>();
        for (int s = 0; s <= last; s++) {
            predecessors.add(new ArrayList<>());
        }
        for (int s = 0; s < last; s++) {
            if (reachable[s]) {
                int from = s;
                successors.of(s, to -> predecessors.get(to).add(from));
            }
        }
        boolean[] canFinish = new boolean[last + 1];
        canFinish[last] = true;
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(last));
        while (!queue.isEmpty()) {
            for (int p : predecessors.get(queue.poll())) {
                if (!canFinish[p]) {
                    canFinish[p] = true;
                    queue.add(p);
                }
            }
        }
        for (int s = 0; s < last; s++) {
            if (reachable[s] && !canFinish[s]) {
                return OptionalInt.of(s);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Expected rolls to finish, exactly: the absorbing-chain fundamental equation {@code (I − Q) t = 1}, solved by Gaussian elimination over the reachable resting squares ({@link #restingSquares}: 81 on the DataGenetics board, under every rule). O(n³). {@code +∞} if a player can get stuck.
     */
    public static double expectedRolls(Board board, FinishRule rule, int faces) {
        if (stuckSquare(board, rule, faces).isPresent()) {
            return Double.POSITIVE_INFINITY;
        }
        int last = board.lastSquare();
        boolean[] reachable = reachable(last, (square, out) -> rollOutcomes(board, rule, faces, square, out));
        int[] index = new int[last + 1];
        Arrays.fill(index, -1);
        int n = 0;
        for (int s = 0; s < last; s++) {
            if (reachable[s]) {
                index[s] = n++;
            }
        }
        double[][] a = new double[n][n + 1]; // augmented [I − Q | 1]
        for (int s = 0; s < last; s++) {
            if (index[s] < 0) {
                continue;
            }
            int row = index[s];
            a[row][row] += 1.0;
            a[row][n] = 1.0;
            for (int roll = 1; roll <= faces; roll++) {
                int next = next(board, rule, s, roll);
                if (next != last) {
                    a[row][index[next]] -= 1.0 / faces;
                }
            }
        }
        return solve(a, n)[index[0]];
    }

    /**
     * {@code p[k]} = probability that the game ends on exactly roll {@code k}, for {@code k = 1..maxRolls} ({@code p[0] = 0}). Computed by pushing the probability vector forward one roll at a time. O(maxRolls × squares × faces).
     */
    public static double[] finishProbabilities(Board board, FinishRule rule, int faces, int maxRolls) {
        checkDice(board, rule, faces);
        int last = board.lastSquare();
        double[] p = new double[maxRolls + 1];
        double[] mass = new double[last + 1];
        mass[0] = 1.0;
        for (int k = 1; k <= maxRolls; k++) {
            double[] nextMass = new double[last + 1];
            for (int s = 0; s < last; s++) {
                if (mass[s] != 0) {
                    for (int roll = 1; roll <= faces; roll++) {
                        nextMass[next(board, rule, s, roll)] += mass[s] / faces;
                    }
                }
            }
            p[k] = nextMass[last];
            nextMass[last] = 0;
            mass = nextMass;
        }
        return p;
    }

    /** The one transition function, shared by every analysis. {@link Game#takeTurn()} makes the same two calls. */
    static int next(Board board, FinishRule rule, int square, int roll) {
        return board.destination(rule.land(square, roll, board.lastSquare()));
    }

    /** The Markov chain's transient states: squares other than the last that a token can rest on. The matrix size n. */
    static int restingSquares(Board board, FinishRule rule, int faces) {
        checkDice(board, rule, faces);
        int last = board.lastSquare();
        boolean[] reachable = reachable(last, (square, out) -> rollOutcomes(board, rule, faces, square, out));
        int n = 0;
        for (int s = 0; s < last; s++) {
            n += reachable[s] ? 1 : 0;
        }
        return n;
    }

    /** Where a token on {@code square} can be next, as a graph edge list. */
    @FunctionalInterface
    private interface Successors {
        /** Edge-list view of the board: calls {@code out} for every square a token can reach from {@code square}. */
        void of(int square, IntConsumer out);
    }

    /** Reports every square one roll can lead to from this square, after the finish rule and any jump. */
    private static void rollOutcomes(Board board, FinishRule rule, int faces, int square, IntConsumer out) {
        for (int roll = 1; roll <= faces; roll++) {
            out.accept(next(board, rule, square, roll));
        }
    }

    /**
     * Every square a whole turn can end on, under the extra roll and the three-sixes forfeit, mirroring {@link Game#takeTurn()}. A non-top roll ends the turn; a top roll moves and rolls again; the third top roll moves nothing and sends the token back to {@code start}.
     */
    private static void turnOutcomes(Board board, FinishRule rule, int faces, int start, IntConsumer out) {
        int last = board.lastSquare();
        int at = start;
        for (int tops = 0; ; tops++) {
            for (int roll = 1; roll < faces; roll++) {
                out.accept(next(board, rule, at, roll));
            }
            if (tops == 2) {
                out.accept(start);
                return;
            }
            at = next(board, rule, at, faces);
            if (at == last) {
                out.accept(last);
                return;
            }
        }
    }

    /** Walks the board from square 0 and marks every square a token can reach. Used to find squares that cannot be left. */
    private static boolean[] reachable(int last, Successors successors) {
        boolean[] seen = new boolean[last + 1];
        seen[0] = true;
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(0));
        while (!queue.isEmpty()) {
            int square = queue.poll();
            if (square != last) {
                successors.of(square, next -> {
                    if (!seen[next]) {
                        seen[next] = true;
                        queue.add(next);
                    }
                });
            }
        }
        return seen;
    }

    /** Rejects dice with fewer than 2 faces and any die the finish rule cannot handle. */
    private static void checkDice(Board board, FinishRule rule, int faces) {
        if (faces < 2) {
            throw new IllegalArgumentException("a die needs at least 2 faces: " + faces);
        }
        rule.checkDice(board.lastSquare(), faces);
    }

    /** Gaussian elimination with partial pivoting on an n × (n+1) augmented matrix. */
    private static double[] solve(double[][] a, int n) {
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            for (int r = col + 1; r < n; r++) {
                double factor = a[r][col] / a[col][col];
                if (factor != 0) {
                    for (int c = col; c <= n; c++) {
                        a[r][c] -= factor * a[col][c];
                    }
                }
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = a[r][n];
            for (int c = r + 1; c < n; c++) {
                sum -= a[r][c] * x[c];
            }
            x[r] = sum / a[r][r];
        }
        return x;
    }
}
