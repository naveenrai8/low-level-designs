package com.learning.auction.service;

import com.learning.auction.model.Auction;
import com.learning.auction.model.AuctionStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AuctionService {
    private final ConcurrentHashMap<String, Auction> auctions = new ConcurrentHashMap<>();

    public Auction createAuction(String itemName, double startingPrice, long durationMinutes) {
        String auctionId = UUID.randomUUID().toString();
        Instant endTime = Instant.now().plusSeconds(durationMinutes * 60);
        Auction auction = new Auction(auctionId, itemName, startingPrice, endTime);
        auctions.put(auctionId, auction);
        return auction;
    }

    public boolean placeBid(String auctionId, String bidderId, double amount) {
        Auction auction = auctions.get(auctionId);
        return auction != null && auction.placeBid(bidderId, amount);
    }

    public Auction getAuction(String auctionId) {
        return auctions.get(auctionId);
    }

    public List<Auction> getActiveAuctions() {
        return auctions.values().stream()
                .filter(auction -> auction.getStatus() == AuctionStatus.ACTIVE)
                .collect(Collectors.toList());
    }

    public List<Auction> getAllAuctions() {
        return List.copyOf(auctions.values());
    }
}
