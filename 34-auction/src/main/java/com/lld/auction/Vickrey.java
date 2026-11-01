package com.lld.auction;

import java.util.Comparator;
import java.util.List;

/**
 * The pure sealed-bid second-price rule (Vickrey): the highest bid wins but pays the second-highest bid, or the reserve if that is higher. Because the price does not depend on your own bid, telling the truth is never worse than shading.
 */
public final class Vickrey {

    /** Not instantiable. */
    private Vickrey() {
    }

    /**
     * Picks the winner and price. Order: highest amount first; an equal amount goes to the smaller {@code seq} (earlier bid). No sale if there are no bids or the top bid is below the reserve. Otherwise the winner pays {@code max(secondHighest, reserve)}, and with a single bid that is the reserve. With a tie at the top the second-highest equals the top, so the winner pays their own amount.
     */
    public static Outcome settle(List<SealedBid> bids, long reserve) {
        List<SealedBid> ranked = bids.stream()
                .sorted(Comparator.comparingLong(SealedBid::amount).reversed().thenComparingLong(SealedBid::seq))
                .toList();
        if (ranked.isEmpty() || ranked.get(0).amount() < reserve) {
            return Outcome.NO_SALE;
        }
        long second = ranked.size() > 1 ? ranked.get(1).amount() : 0;
        return new Outcome(true, ranked.get(0).bidder(), Math.max(second, reserve));
    }
}
