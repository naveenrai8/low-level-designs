package com.learning.auction.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class Auction {
    private final String auctionId;
    private final String itemName;
    private final double startingPrice;
    private final Instant endTime;
    private final AtomicReference<Bid> highestBid;
    private volatile AuctionStatus status;

    public Auction(String auctionId, String itemName, double startingPrice, Instant endTime) {
        this.auctionId = auctionId;
        this.itemName = itemName;
        this.startingPrice = startingPrice;
        this.endTime = endTime;
        this.highestBid = new AtomicReference<>(new Bid(null, startingPrice));
        this.status = AuctionStatus.ACTIVE;
    }

    public boolean placeBid(String bidderId, double amount) {
        if (status != AuctionStatus.ACTIVE || Instant.now().isAfter(endTime)) {
            status = AuctionStatus.CLOSED;
            return false;
        }

        return highestBid.updateAndGet(current ->
            amount > current.amount() ? new Bid(bidderId, amount) : current
        ).bidderId() != null && highestBid.get().amount() == amount;
    }

    public String getAuctionId() { return auctionId; }
    public String getItemName() { return itemName; }
    public double getStartingPrice() { return startingPrice; }
    public Instant getEndTime() { return endTime; }
    public Bid getHighestBid() { return highestBid.get(); }
    public AuctionStatus getStatus() { return status; }
}
