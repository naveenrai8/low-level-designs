package com.learning.tictactoe.model;

public class TicTacToeGame {
    private final String gameId;
    private final char[][] board;
    private char currentPlayer;
    private GameStatus status;
    private String winner;

    public TicTacToeGame(String gameId) {
        this.gameId = gameId;
        this.board = new char[3][3];
        this.currentPlayer = 'X';
        this.status = GameStatus.IN_PROGRESS;
        initializeBoard();
    }

    private void initializeBoard() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                board[i][j] = '-';
            }
        }
    }

    public synchronized boolean makeMove(int row, int col) {
        if (status != GameStatus.IN_PROGRESS || board[row][col] != '-') {
            return false;
        }

        board[row][col] = currentPlayer;

        if (checkWin()) {
            status = GameStatus.COMPLETED;
            winner = String.valueOf(currentPlayer);
        } else if (isBoardFull()) {
            status = GameStatus.DRAW;
        } else {
            currentPlayer = (currentPlayer == 'X') ? 'O' : 'X';
        }

        return true;
    }

    private boolean checkWin() {
        // Check rows and columns
        for (int i = 0; i < 3; i++) {
            if (board[i][0] == currentPlayer && board[i][1] == currentPlayer && board[i][2] == currentPlayer) return true;
            if (board[0][i] == currentPlayer && board[1][i] == currentPlayer && board[2][i] == currentPlayer) return true;
        }
        // Check diagonals
        if (board[0][0] == currentPlayer && board[1][1] == currentPlayer && board[2][2] == currentPlayer) return true;
        if (board[0][2] == currentPlayer && board[1][1] == currentPlayer && board[2][0] == currentPlayer) return true;
        return false;
    }

    private boolean isBoardFull() {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (board[i][j] == '-') return false;
            }
        }
        return true;
    }

    public String getGameId() { return gameId; }
    public char[][] getBoard() { return board; }
    public char getCurrentPlayer() { return currentPlayer; }
    public GameStatus getStatus() { return status; }
    public String getWinner() { return winner; }
}
