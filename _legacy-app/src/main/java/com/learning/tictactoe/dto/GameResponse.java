package com.learning.tictactoe.dto;

import com.learning.tictactoe.model.GameStatus;

public record GameResponse(
    String gameId,
    char[][] board,
    char currentPlayer,
    GameStatus status,
    String winner
) {}
