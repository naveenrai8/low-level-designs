package com.learning.concert.controller;

import com.learning.concert.model.Concert;
import com.learning.concert.service.ConcertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/concert")
@Tag(name = "Concert", description = "Concert ticket booking operations")
public class ConcertController {

    private final ConcertService concertService;

    public ConcertController(ConcertService concertService) {
        this.concertService = concertService;
    }

    @PostMapping("/concerts")
    @Operation(summary = "Create a concert")
    public ResponseEntity<Concert> createConcert(
            @RequestParam String concertId,
            @RequestParam String artist,
            @RequestParam String venue,
            @RequestParam LocalDateTime dateTime,
            @RequestParam int totalSeats) {
        Concert concert = concertService.createConcert(concertId, artist, venue, dateTime, totalSeats);
        return ResponseEntity.status(HttpStatus.CREATED).body(concert);
    }

    @GetMapping("/concerts")
    @Operation(summary = "Get all concerts")
    public ResponseEntity<List<Concert>> getAllConcerts() {
        return ResponseEntity.ok(concertService.getAllConcerts());
    }

    @GetMapping("/concerts/{concertId}")
    @Operation(summary = "Get concert details")
    public ResponseEntity<Concert> getConcert(@PathVariable String concertId) {
        Concert concert = concertService.getConcert(concertId);
        return concert != null ? ResponseEntity.ok(concert) : ResponseEntity.notFound().build();
    }

    @PostMapping("/concerts/{concertId}/book/{seatId}")
    @Operation(summary = "Book a seat")
    public ResponseEntity<String> bookSeat(@PathVariable String concertId, @PathVariable String seatId) {
        boolean success = concertService.bookSeat(concertId, seatId);
        return success ? ResponseEntity.ok("Seat booked successfully")
                       : ResponseEntity.badRequest().body("Seat not available");
    }
}
