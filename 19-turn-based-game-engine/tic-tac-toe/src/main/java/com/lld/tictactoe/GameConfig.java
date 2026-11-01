package com.lld.tictactoe;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The rules of one game: an N×N board, K in a row to win, and two or more players who move in list order.
 *
 * <p>Invalid configs are caller bugs, so they throw {@link IllegalArgumentException} here, once, instead of producing strange games later.
 *
 * @param size N, at least 1
 * @param winLength K, with {@code 1 <= K <= N}
 * @param players at least 2, in turn order; ids and symbols unique (defensively copied)
 */
public record GameConfig(int size, int winLength, List<Player> players) {

    /** Validates the rules once, when the record is built. Also takes a private copy of the player list so later changes by the caller cannot alter the game. */
    public GameConfig {
        players = List.copyOf(Objects.requireNonNull(players, "players")); // snapshot, rejects nulls
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1: " + size);
        }
        if (winLength < 1 || winLength > size) {
            throw new IllegalArgumentException("winLength must be in [1, " + size + "]: " + winLength);
        }
        if (players.size() < 2) {
            throw new IllegalArgumentException("need at least 2 players: " + players.size());
        }
        Set<String> ids = new HashSet<>();
        Set<Character> symbols = new HashSet<>();
        for (Player p : players) {
            if (!ids.add(p.id())) {
                throw new IllegalArgumentException("duplicate player id: " + p.id());
            }
            if (!symbols.add(p.symbol())) {
                throw new IllegalArgumentException("duplicate symbol: " + p.symbol());
            }
        }
    }

    /** The classic game: 3×3, three in a row, X moves first. */
    public static GameConfig classic() {
        return new GameConfig(3, 3, List.of(new Player("X", 'X'), new Player("O", 'O')));
    }
}
