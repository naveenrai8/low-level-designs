package com.lld.tictactoe;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The thread-safe front door for many concurrent games, e.g. behind a game server.
 *
 * <h2>Concurrency model</h2>
 * <ul>
 *   <li><b>Shared state:</b> each {@link Game}, plus the id → game map.
 *   <li><b>Invariant:</b> a game's check-turn → place → advance-turn runs atomically, so two submissions for the same turn (a double click, two devices) produce exactly one accepted move.
 *   <li><b>Primitive:</b> one monitor per game. Games never share a lock, so traffic on one game never waits for another. The map is a {@link ConcurrentHashMap}, so lookups don't lock.
 *   <li><b>Why not {@code compute()} on the map?</b> It would also work, but it holds the hash bin's lock, which unrelated games can share. A per-game lock says exactly what's protected.
 *   <li><b>No leaks:</b> the registry never hands out a live {@link Game}. {@link #snapshot} returns a detached copy, so nobody can mutate a game without holding its lock.
 * </ul>
 */
public final class GameRegistry {

    /** Opaque game id. */
    public record GameId(long value) {}

    /** Holder for one game. The registry locks on this object, so each game has its own lock. */
    private static final class Slot {
        final Game game;

        /** Wraps a game so it can be locked. */
        Slot(Game game) {
            this.game = game;
        }
    }

    private final ConcurrentHashMap<GameId, Slot> games = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final Function<GameConfig, Game> newGame;

    /** Creates an empty registry that builds ordinary {@link Game} objects. */
    public GameRegistry() {
        this(Game::new);
    }

    /** Lets tests create games with a race-window hook. */
    GameRegistry(Function<GameConfig, Game> newGame) {
        this.newGame = Objects.requireNonNull(newGame, "newGame");
    }

    /** Starts a new game with the given rules and returns its id. */
    public GameId create(GameConfig config) {
        GameId id = new GameId(nextId.incrementAndGet());
        games.put(id, new Slot(newGame.apply(Objects.requireNonNull(config, "config"))));
        return id;
    }

    /**
     * Submits a move to a game.
     *
     * @throws NoSuchElementException if the game doesn't exist
     * @throws IllegalArgumentException if the player isn't in the game
     */
    public MoveResult submit(GameId id, String playerId, int row, int col) {
        Slot slot = slot(id);
        synchronized (slot) {
            return slot.game.play(playerId, row, col);
        }
    }

    /** A detached copy of the game, taken under its lock. */
    public Game snapshot(GameId id) {
        Slot slot = slot(id);
        synchronized (slot) {
            return slot.game.copy();
        }
    }

    /** Forgets a game, e.g. once it's over and archived. */
    public boolean remove(GameId id) {
        return games.remove(id) != null;
    }

    /** How many games the registry currently holds. */
    public int size() {
        return games.size();
    }

    /** Finds the slot for a game id. Throws {@link NoSuchElementException} if the id is unknown. */
    private Slot slot(GameId id) {
        Slot slot = games.get(Objects.requireNonNull(id, "id"));
        if (slot == null) {
            throw new NoSuchElementException("unknown game: " + id.value());
        }
        return slot;
    }
}
