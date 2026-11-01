package com.lld.tictactoe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lld.tictactoe.MoveResult.Accepted;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Re-runs the README's measured numbers. Off by default, because timings depend on the machine and the race count takes seconds. Run it alone, so "first call" really is the JVM's first search:
 *
 * <pre>mvn -o -q -pl 19-turn-based-game-engine/tic-tac-toe test -Dtest=MeasurementsTest -Dttt.measure=true</pre>
 *
 * <p>Wall-clock {@code System.nanoTime()} around single runs. Not a JMH benchmark: no forks, no steady-state warm-up, no error bars. Read the numbers as orders of magnitude.
 */
@EnabledIfSystemProperty(named = "ttt.measure", matches = "true")
@TestMethodOrder(OrderAnnotation.class)
class MeasurementsTest {

    /** Measures how long minimax takes to search an empty board. This is a measurement printed for the README, not a pass or fail speed test. */
    @Test
    @Order(1)
    void minimaxEmptyBoardSearch() {
        long first = timeEmptyBoardSearch();
        long[] next = new long[20];
        for (int i = 0; i < next.length; i++) {
            next[i] = timeEmptyBoardSearch();
        }
        Arrays.sort(next);
        System.out.printf("minimax, empty board, fresh strategy per call (no memo hit): first %.1f ms;"
                        + " next 20: median %.1f ms, min %.1f, max %.1f%n",
                ms(first), ms(next[next.length / 2]), ms(next[0]), ms(next[next.length - 1]));
    }

    /** Times one minimax search from an empty board, in nanoseconds. */
    private static long timeEmptyBoardSearch() {
        MinimaxStrategy fresh = new MinimaxStrategy();
        Game game = new Game(GameConfig.classic());
        long start = System.nanoTime();
        Cell choice = fresh.choose(game);
        long elapsed = System.nanoTime() - start;
        assertEquals(new Cell(0, 0), choice);
        return elapsed;
    }

    /** Measures 1,000 games of perfect play against random play, with and without the memo cache, to show what the cache saves. */
    @Test
    @Order(2)
    void thousandGamesAgainstRandomWithAndWithoutMemo() {
        for (int run = 1; run <= 2; run++) {
            MinimaxStrategy shared = new MinimaxStrategy();
            long withMemo = timeThousandGames(shared);
            long withoutMemo = timeThousandGames(game -> new MinimaxStrategy().choose(game));
            System.out.printf("1,000 games vs random (seed 42), run %d: shared memo %.0f ms, fresh strategy per move %.0f ms%n",
                    run, ms(withMemo), ms(withoutMemo));
        }
    }

    /** Plays 1,000 seeded games of the given strategy against a random player and returns the elapsed nanoseconds. */
    private static long timeThousandGames(MoveStrategy perfect) {
        SplittableRandom seeds = new SplittableRandom(42);
        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            MoveStrategy random = new RandomStrategy(seeds.split());
            Map<String, MoveStrategy> seats = i % 2 == 0
                    ? Map.of("X", perfect, "O", random)
                    : Map.of("X", random, "O", perfect);
            Game game = new Game(GameConfig.classic());
            while (game.status() == Status.IN_PROGRESS) {
                String who = game.playerToMove().orElseThrow().id();
                Cell cell = seats.get(who).choose(game);
                game.play(who, cell.row(), cell.col());
            }
        }
        return System.nanoTime() - start;
    }

    /**
     * What an unlocked Game does with two same-turn moves. Besides "both accepted", the unsynchronised {@code ArrayList} history can throw (first seen as an ArrayIndexOutOfBoundsException).
     */
    @Test
    @Order(3)
    void unlockedGameRace() throws Exception {
        int rounds = 100_000;
        int bothAccepted = 0;
        int threw = 0;
        Set<String> thrown = new TreeSet<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long start = System.nanoTime();
        try {
            for (int round = 0; round < rounds; round++) {
                Game game = new Game(GameConfig.classic()); // no registry, no lock
                CyclicBarrier startGate = new CyclicBarrier(2);
                Future<MoveResult> a = pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return game.play("X", 0, 0);
                });
                Future<MoveResult> b = pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return game.play("X", 1, 1);
                });
                MoveResult ra = outcome(a, thrown);
                MoveResult rb = outcome(b, thrown);
                if (ra == null || rb == null) {
                    threw++;
                } else if (ra instanceof Accepted && rb instanceof Accepted) {
                    bothAccepted++;
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        System.out.printf("unlocked Game, 2 threads, same turn, %,d rounds: both accepted %d (%.3f%%), a move threw %d %s; %.1f s%n",
                rounds, bothAccepted, 100.0 * bothAccepted / rounds, threw, thrown, (System.nanoTime() - start) / 1e9);
    }

    /** The move's result, or null if it threw (the exception's class is recorded). */
    private static MoveResult outcome(Future<MoveResult> move, Set<String> thrown) throws Exception {
        try {
            return move.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            thrown.add(e.getCause().getClass().getSimpleName());
            return null;
        }
    }

    /** Turns nanoseconds into milliseconds for printing. */
    private static double ms(long nanos) {
        return nanos / 1e6;
    }
}
