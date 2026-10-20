package com.learning.chess.controller;

import com.learning.chess.model.ChessGame;
import com.learning.chess.service.ChessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chess")
@Tag(name = "Chess", description = "Chess game operations")
public class ChessController {

    private final ChessService chessService;

    public ChessController(ChessService chessService) {
        this.chessService = chessService;
    }

    @PostMapping("/games")
    @Operation(summary = "Create a chess game")
    public ResponseEntity<ChessGame> createGame(@RequestParam String gameId) {
        ChessGame game = chessService.createGame(gameId);
        return ResponseEntity.status(HttpStatus.CREATED).body(game);
    }

    @GetMapping("/games")
    @Operation(summary = "Get all games")
    public ResponseEntity<List<ChessGame>> getAllGames() {
        return ResponseEntity.ok(chessService.getAllGames());
    }

    @GetMapping("/games/{gameId}")
    @Operation(summary = "Get game details")
    public ResponseEntity<ChessGame> getGame(@PathVariable String gameId) {
        ChessGame game = chessService.getGame(gameId);
        return game != null ? ResponseEntity.ok(game) : ResponseEntity.notFound().build();
    }

    @PostMapping("/games/{gameId}/move")
    @Operation(summary = "Make a move")
    public ResponseEntity<String> makeMove(
            @PathVariable String gameId,
            @RequestParam int fromRow,
            @RequestParam int fromCol,
            @RequestParam int toRow,
            @RequestParam int toCol) {
        boolean success = chessService.makeMove(gameId, fromRow, fromCol, toRow, toCol);
        return success ? ResponseEntity.ok("Move successful")
                       : ResponseEntity.badRequest().body("Invalid move");
    }
}
