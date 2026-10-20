package com.learning.snakeladder.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SnakeLadderGame {
    private final String gameId;
    private final Map<Integer, Integer> snakes;
    private final Map<Integer, Integer> ladders;
    private final ConcurrentHashMap<String, Integer> playerPositions;
    private final List<String> players;
    private int currentPlayerIndex;

    public SnakeLadderGame(String gameId, List<String> players) {
        this.gameId = gameId;
        this.players = players;
        this.playerPositions = new ConcurrentHashMap<>();
        this.snakes = initializeSnakes();
        this.ladders = initializeLadders();
        this.currentPlayerIndex = 0;

        players.forEach(p -> playerPositions.put(p, 0));
    }

    private Map<Integer, Integer> initializeSnakes() {
        return Map.of(16, 6, 47, 26, 49, 11, 56, 53, 62, 19, 64, 60, 87, 24, 93, 73, 95, 75, 98, 78);
    }

    private Map<Integer, Integer> initializeLadders() {
        return Map.of(1, 38, 4, 14, 9, 31, 21, 42, 28, 84, 36, 44, 51, 67, 71, 91, 80, 100);
    }

    public synchronized int rollDice() {
        return new Random().nextInt(6) + 1;
    }

    public synchronized boolean makeMove(int diceRoll) {
        String currentPlayer = players.get(currentPlayerIndex);
        int currentPos = playerPositions.get(currentPlayer);
        int newPos = currentPos + diceRoll;

        if (newPos > 100) return false;

        if (snakes.containsKey(newPos)) newPos = snakes.get(newPos);
        if (ladders.containsKey(newPos)) newPos = ladders.get(newPos);

        playerPositions.put(currentPlayer, newPos);
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();

        return newPos == 100;
    }

    public String getGameId() { return gameId; }
    public Map<String, Integer> getPlayerPositions() { return new HashMap<>(playerPositions); }
}
