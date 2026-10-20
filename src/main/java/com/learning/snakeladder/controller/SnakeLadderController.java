package com.learning.snakeladder.controller;

import com.learning.snakeladder.dto.GameRequest;
import com.learning.snakeladder.dto.GameResponse;
import com.learning.snakeladder.model.SnakeLadderGame;
import com.learning.snakeladder.service.SnakeLadderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/snakeladder")
@Tag(name = "Snake & Ladder", description = "Snake and Ladder game operations")
public class SnakeLadderController {

    private final SnakeLadderService snakeLadderService;

    public SnakeLadderController(SnakeLadderService snakeLadderService) {
        this.snakeLadderService = snakeLadderService;
    }

    @PostMapping("/games")
    @Operation(summary = "Create a new game")
    public ResponseEntity<GameResponse> createGame(@Valid @RequestBody GameRequest request) {
        SnakeLadderGame game = snakeLadderService.createGame(request.players());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(game));
    }

    @GetMapping("/games/{gameId}")
    @Operation(summary = "Get game status")
    public ResponseEntity<GameResponse> getGame(@PathVariable String gameId) {
        SnakeLadderGame game = snakeLadderService.getGame(gameId);
        return game != null ? ResponseEntity.ok(toResponse(game))
                             : ResponseEntity.notFound().build();
    }

    @PostMapping("/games/{gameId}/roll")
    @Operation(summary = "Roll dice and make move")
    public ResponseEntity<Map<String, Object>> rollAndMove(@PathVariable String gameId) {
        int diceRoll = snakeLadderService.rollDice(gameId);
        boolean won = snakeLadderService.makeMove(gameId, diceRoll);
        SnakeLadderGame game = snakeLadderService.getGame(gameId);

        return ResponseEntity.ok(Map.of(
            "diceRoll", diceRoll,
            "won", won,
            "gameState", toResponse(game)
        ));
    }

    private GameResponse toResponse(SnakeLadderGame game) {
        return new GameResponse(
            game.getGameId(),
            game.getPlayerPositions(),
            "Active"
        );
    }
}
