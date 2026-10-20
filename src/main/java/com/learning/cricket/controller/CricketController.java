package com.learning.cricket.controller;

import com.learning.cricket.model.Match;
import com.learning.cricket.service.CricketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cricket")
@Tag(name = "Cricket", description = "Cricket information system operations")
public class CricketController {

    private final CricketService cricketService;

    public CricketController(CricketService cricketService) {
        this.cricketService = cricketService;
    }

    @PostMapping("/matches")
    @Operation(summary = "Create a match")
    public ResponseEntity<Match> createMatch(
            @RequestParam String matchId,
            @RequestParam String team1,
            @RequestParam String team2,
            @RequestParam String venue,
            @RequestParam java.time.LocalDateTime matchTime) {
        Match match = cricketService.createMatch(matchId, team1, team2, venue, matchTime);
        return ResponseEntity.status(HttpStatus.CREATED).body(match);
    }

    @GetMapping("/matches")
    @Operation(summary = "Get all matches")
    public ResponseEntity<List<Match>> getAllMatches() {
        return ResponseEntity.ok(cricketService.getAllMatches());
    }

    @GetMapping("/matches/{matchId}")
    @Operation(summary = "Get match details")
    public ResponseEntity<Match> getMatch(@PathVariable String matchId) {
        Match match = cricketService.getMatch(matchId);
        return match != null ? ResponseEntity.ok(match) : ResponseEntity.notFound().build();
    }

    @PutMapping("/matches/{matchId}/score")
    @Operation(summary = "Update score")
    public ResponseEntity<String> updateScore(
            @PathVariable String matchId,
            @RequestParam String team,
            @RequestParam int runs) {
        cricketService.updateScore(matchId, team, runs);
        return ResponseEntity.ok("Score updated");
    }
}
