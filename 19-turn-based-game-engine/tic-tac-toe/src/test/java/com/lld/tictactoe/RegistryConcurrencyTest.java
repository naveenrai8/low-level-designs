package com.lld.tictactoe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.lld.tictactoe.GameRegistry.GameId;
import com.lld.tictactoe.MoveResult.Accepted;
import com.lld.tictactoe.MoveResult.Reason;
import com.lld.tictactoe.MoveResult.Rejected;
import java.util.NoSuchElementException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Two submissions for the same turn (X double-clicks two cells from two devices).
 *
 * <ul>
 *   <li>The first two tests are deterministic. They use the race-window hook in {@code Game.play} (after the turn check, before the board changes) to force the interleaving.
 *   <li>The hammer test adds exact totals over 1,000 real races. On its own it is a weak guard: an unlocked {@code Game} double-accepted in only 19 and 25 of 100,000 rounds (measured, two runs, Apple M3 Max). That is why the deterministic tests exist.
 * </ul>
 */
class RegistryConcurrencyTest {

    private static final int ROUNDS = 1_000;

    /** Proves the race is real without the registry: a bare game lets two submissions for the same turn both through. */
    @Test
    void bareGameLetsTwoMovesThroughOneTurn() {
        AtomicReference<Game> self = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<MoveResult> inner = new AtomicReference<>();
        Game game = new Game(GameConfig.classic(), () -> {
            if (fired.compareAndSet(false, true)) {
                inner.set(self.get().play("X", 1, 1)); // same-thread probe: a rival move inside the gap
            }
        });
        self.set(game);

        MoveResult outer = game.play("X", 0, 0);

        assertInstanceOf(Accepted.class, inner.get());
        assertInstanceOf(Accepted.class, outer);
        assertEquals(2, game.history().size(), "X moved twice in one turn");
    }

    /** Proves the registry holds the game's lock across the race window, so the second submission waits and is then refused. */
    @Test
    void registryHoldsTheGameLockAcrossTheRaceWindow() throws Exception {
        AtomicReference<GameRegistry> registry = new AtomicReference<>();
        AtomicReference<GameId> id = new AtomicReference<>();
        AtomicReference<MoveResult> rivalResult = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean();
        Thread rival = new Thread(() -> rivalResult.set(registry.get().submit(id.get(), "X", 1, 1)));
        registry.set(new GameRegistry(config -> new Game(config, () -> {
            if (fired.compareAndSet(false, true)) {
                rival.start();
                awaitBlocked(rival); // the rival must wait on this game's lock, not run inside the gap
            }
        })));
        id.set(registry.get().create(GameConfig.classic()));

        MoveResult first = registry.get().submit(id.get(), "X", 0, 0);
        rival.join(TimeUnit.SECONDS.toMillis(5));

        assertInstanceOf(Accepted.class, first);
        assertEquals(new Rejected(Reason.NOT_YOUR_TURN), rivalResult.get());
        assertEquals(1, registry.get().snapshot(id.get()).history().size());
    }

    /** Proves that one game's lock does not block a different game, because games never share a lock. */
    @Test
    void holdingOneGamesLockDoesNotBlockAnotherGame() {
        AtomicReference<GameRegistry> registry = new AtomicReference<>();
        AtomicReference<GameId> other = new AtomicReference<>();
        AtomicReference<MoveResult> otherResult = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean();
        Thread elsewhere = new Thread(() -> otherResult.set(registry.get().submit(other.get(), "X", 0, 0)));
        registry.set(new GameRegistry(config -> new Game(config, () -> {
            if (fired.compareAndSet(false, true)) {
                elsewhere.start();
                awaitTerminated(elsewhere); // game B's move must finish while game A's lock is held
            }
        })));
        GameId held = registry.get().create(GameConfig.classic());
        other.set(registry.get().create(GameConfig.classic()));

        assertInstanceOf(Accepted.class, registry.get().submit(held, "X", 0, 0));
        assertInstanceOf(Accepted.class, otherResult.get());
    }

    /** Spins (no sleep) until the thread finishes. A thread stuck on a shared lock never does. */
    private static void awaitTerminated(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.TERMINATED) {
            if (System.nanoTime() - deadline > 0) {
                fail("a move on another game is stuck in state " + thread.getState() + ": the lock isn't per game");
            }
            Thread.onSpinWait();
        }
    }

    /** Spins (no sleep) until the thread blocks on a monitor. Fails if it finished instead. */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            if (thread.getState() == Thread.State.TERMINATED) {
                fail("the rival move ran inside the race window: the game is not locked");
            }
            if (System.nanoTime() - deadline > 0) {
                fail("the rival never reached the game lock");
            }
            Thread.onSpinWait();
        }
    }

    /** Proves that when many threads submit the same turn together, exactly one is accepted and the rest are refused. */
    @Test
    void exactlyOneSubmissionPerTurnIsAccepted() throws Exception {
        GameRegistry registry = new GameRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int accepted = 0;
        int notYourTurn = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                GameId id = registry.create(GameConfig.classic());
                CyclicBarrier startGate = new CyclicBarrier(2);
                Future<MoveResult> a = pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return registry.submit(id, "X", 0, 0);
                });
                Future<MoveResult> b = pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return registry.submit(id, "X", 1, 1);
                });
                for (MoveResult r : new MoveResult[] {a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)}) {
                    if (r instanceof Accepted) {
                        accepted++;
                    } else if (r.equals(new Rejected(Reason.NOT_YOUR_TURN))) {
                        notYourTurn++;
                    }
                }
                assertEquals(1, registry.snapshot(id).history().size(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(ROUNDS, accepted);
        assertEquals(ROUNDS, notYourTurn);
        assertEquals(ROUNDS, registry.size());
    }

    /** Proves that a snapshot is a detached copy: later moves do not show up in it. */
    @Test
    void snapshotIsDetached() {
        GameRegistry registry = new GameRegistry();
        GameId id = registry.create(GameConfig.classic());

        registry.snapshot(id).play("X", 0, 0);

        assertEquals(0, registry.snapshot(id).history().size());
    }

    /** Proves that asking for a game that does not exist throws {@link NoSuchElementException}. */
    @Test
    void unknownGameIsAnError() {
        GameRegistry registry = new GameRegistry();
        GameId id = registry.create(GameConfig.classic());
        registry.remove(id);

        assertThrows(NoSuchElementException.class, () -> registry.submit(id, "X", 0, 0));
    }
}
