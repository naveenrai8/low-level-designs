package com.learning.snakeladder.dto;

import java.util.Map;

public record GameResponse(
    String gameId,
    Map<String, Integer> playerPositions,
    String status
) {}
