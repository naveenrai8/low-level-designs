package com.learning.tictactoe.service;

import com.learning.tictactoe.model.TicTacToeGame;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TicTacToeService {
    private final ConcurrentHashMap<String, TicTacToeGame> games = new ConcurrentHashMap<>();

    public TicTacToeGame createGame() {
        String gameId = UUID.randomUUID().toString();
        TicTacToeGame game = new TicTacToeGame(gameId);
        games.put(gameId, game);
        return game;
    }

    public TicTacToeGame getGame(String gameId) {
        return games.get(gameId);
    }

    public boolean makeMove(String gameId, int row, int col) {
        TicTacToeGame game = games.get(gameId);
        return game != null && game.makeMove(row, col);
    }
}
