package com.learning.chess.service;

import com.learning.chess.model.ChessGame;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChessService {
    private final ConcurrentHashMap<String, ChessGame> games = new ConcurrentHashMap<>();

    public ChessGame createGame(String gameId) {
        ChessGame game = new ChessGame(gameId);
        games.put(gameId, game);
        return game;
    }

    public boolean makeMove(String gameId, int fromRow, int fromCol, int toRow, int toCol) {
        ChessGame game = games.get(gameId);
        return game != null && game.makeMove(fromRow, fromCol, toRow, toCol);
    }

    public ChessGame getGame(String gameId) {
        return games.get(gameId);
    }

    public List<ChessGame> getAllGames() {
        return List.copyOf(games.values());
    }
}
