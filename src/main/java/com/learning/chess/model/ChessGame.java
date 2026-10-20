package com.learning.chess.model;

public class ChessGame {
    private final String gameId;
    private final String[][] board;
    private String currentPlayer;
    private GameState state;

    public ChessGame(String gameId) {
        this.gameId = gameId;
        this.board = new String[8][8];
        this.currentPlayer = "WHITE";
        this.state = GameState.IN_PROGRESS;
        initializeBoard();
    }

    private void initializeBoard() {
        // Initialize with standard chess starting position
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                board[i][j] = "EMPTY";
            }
        }
        // Setup pieces (simplified)
        board[0][0] = "B_ROOK"; board[0][7] = "B_ROOK";
        board[7][0] = "W_ROOK"; board[7][7] = "W_ROOK";
    }

    public synchronized boolean makeMove(int fromRow, int fromCol, int toRow, int toCol) {
        // Simplified move logic
        if (state != GameState.IN_PROGRESS) return false;

        String piece = board[fromRow][fromCol];
        if (piece.equals("EMPTY")) return false;

        board[toRow][toCol] = piece;
        board[fromRow][fromCol] = "EMPTY";

        currentPlayer = currentPlayer.equals("WHITE") ? "BLACK" : "WHITE";
        return true;
    }

    public String getGameId() { return gameId; }
    public GameState getState() { return state; }
}

enum GameState { IN_PROGRESS, CHECKMATE, STALEMATE, DRAW }
