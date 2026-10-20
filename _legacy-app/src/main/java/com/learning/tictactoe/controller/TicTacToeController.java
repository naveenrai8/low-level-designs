package com.learning.tictactoe.controller;

import com.learning.tictactoe.dto.GameResponse;
import com.learning.tictactoe.dto.MoveRequest;
import com.learning.tictactoe.model.TicTacToeGame;
import com.learning.tictactoe.service.TicTacToeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tictactoe")
@Tag(name = "Tic Tac Toe", description = "Tic Tac Toe game operations")
public class TicTacToeController {

    private final TicTacToeService ticTacToeService;

    public TicTacToeController(TicTacToeService ticTacToeService) {
        this.ticTacToeService = ticTacToeService;
    }

    @PostMapping("/games")
    @Operation(summary = "Create a new game")
    public ResponseEntity<GameResponse> createGame() {
        TicTacToeGame game = ticTacToeService.createGame();
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(game));
    }

    @GetMapping("/games/{gameId}")
    @Operation(summary = "Get game status")
    public ResponseEntity<GameResponse> getGame(@PathVariable String gameId) {
        TicTacToeGame game = ticTacToeService.getGame(gameId);
        if (game == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(game));
    }

    @PostMapping("/games/{gameId}/move")
    @Operation(summary = "Make a move")
    public ResponseEntity<GameResponse> makeMove(
            @PathVariable String gameId,
            @Valid @RequestBody MoveRequest request) {
        boolean success = ticTacToeService.makeMove(gameId, request.row(), request.col());
        if (!success) {
            return ResponseEntity.badRequest().build();
        }
        TicTacToeGame game = ticTacToeService.getGame(gameId);
        return ResponseEntity.ok(toResponse(game));
    }

    private GameResponse toResponse(TicTacToeGame game) {
        return new GameResponse(
            game.getGameId(),
            game.getBoard(),
            game.getCurrentPlayer(),
            game.getStatus(),
            game.getWinner()
        );
    }
}
