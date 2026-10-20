package com.learning.snakeladder.service;

import com.learning.snakeladder.model.SnakeLadderGame;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SnakeLadderService {
    private final ConcurrentHashMap<String, SnakeLadderGame> games = new ConcurrentHashMap<>();

    public SnakeLadderGame createGame(List<String> players) {
        SnakeLadderGame game = new SnakeLadderGame(java.util.UUID.randomUUID().toString(), players);
        games.put(game.getGameId(), game);
        return game;
    }

    public SnakeLadderGame getGame(String gameId) {
        return games.get(gameId);
    }

    public int rollDice(String gameId) {
        SnakeLadderGame game = games.get(gameId);
        return game != null ? game.rollDice() : 0;
    }

    public boolean makeMove(String gameId, int diceRoll) {
        SnakeLadderGame game = games.get(gameId);
        return game != null && game.makeMove(diceRoll);
    }
}
