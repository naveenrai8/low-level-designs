package com.learning.auction.dto;

import com.learning.auction.model.AuctionStatus;
import com.learning.auction.model.Bid;
import java.time.Instant;

public record AuctionResponse(
    String auctionId,
    String itemName,
    double startingPrice,
    Instant endTime,
    Bid highestBid,
    AuctionStatus status
) {}
