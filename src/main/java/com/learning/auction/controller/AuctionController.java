package com.learning.auction.controller;

import com.learning.auction.dto.AuctionRequest;
import com.learning.auction.dto.AuctionResponse;
import com.learning.auction.dto.BidRequest;
import com.learning.auction.model.Auction;
import com.learning.auction.service.AuctionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auction")
@Tag(name = "Auction", description = "Online auction operations")
public class AuctionController {

    private final AuctionService auctionService;

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @PostMapping("/auctions")
    @Operation(summary = "Create a new auction")
    public ResponseEntity<AuctionResponse> createAuction(@Valid @RequestBody AuctionRequest request) {
        Auction auction = auctionService.createAuction(
            request.itemName(),
            request.startingPrice(),
            request.durationMinutes()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(auction));
    }

    @GetMapping("/auctions/{auctionId}")
    @Operation(summary = "Get auction details")
    public ResponseEntity<AuctionResponse> getAuction(@PathVariable String auctionId) {
        Auction auction = auctionService.getAuction(auctionId);
        return auction != null ? ResponseEntity.ok(toResponse(auction))
                                : ResponseEntity.notFound().build();
    }

    @GetMapping("/auctions/active")
    @Operation(summary = "Get all active auctions")
    public ResponseEntity<List<AuctionResponse>> getActiveAuctions() {
        List<AuctionResponse> auctions = auctionService.getActiveAuctions().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(auctions);
    }

    @GetMapping("/auctions")
    @Operation(summary = "Get all auctions")
    public ResponseEntity<List<AuctionResponse>> getAllAuctions() {
        List<AuctionResponse> auctions = auctionService.getAllAuctions().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(auctions);
    }

    @PostMapping("/auctions/{auctionId}/bids")
    @Operation(summary = "Place a bid")
    public ResponseEntity<?> placeBid(
            @PathVariable String auctionId,
            @Valid @RequestBody BidRequest request) {
        boolean success = auctionService.placeBid(auctionId, request.bidderId(), request.amount());
        if (!success) {
            return ResponseEntity.badRequest().body("Bid failed - auction closed or bid too low");
        }
        Auction auction = auctionService.getAuction(auctionId);
        return ResponseEntity.ok(toResponse(auction));
    }

    private AuctionResponse toResponse(Auction auction) {
        return new AuctionResponse(
            auction.getAuctionId(),
            auction.getItemName(),
            auction.getStartingPrice(),
            auction.getEndTime(),
            auction.getHighestBid(),
            auction.getStatus()
        );
    }
}
