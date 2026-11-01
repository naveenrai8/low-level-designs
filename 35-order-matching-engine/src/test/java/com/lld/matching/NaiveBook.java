package com.lld.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately slow reference matcher for differential tests: one flat list of resting orders, and every match is a full scan for the best (price, then arrival) order. It shares no code with {@link LimitOrderBook}.
 */
final class NaiveBook {

    /** One resting order in the flat list. */
    private static final class R {
        final String id;
        final Side side;
        long price;
        long qty;
        long seq;

        /** Creates a resting entry. */
        R(String id, Side side, long price, long qty, long seq) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.qty = qty;
            this.seq = seq;
        }
    }

    private final List<R> list = new ArrayList<>();
    final List<Trade> trades = new ArrayList<>();
    private long seq = 1;
    private long tradeSeq = 1;

    /** Applies a command that is known to be valid, and appends the trades it makes to {@link #trades}. */
    void apply(Command c) {
        switch (c) {
            case Command.NewOrder n -> {
                if (n.type() == OrderType.FOK && crossingQty(n.side(), n.price()) < n.qty()) {
                    return;
                }
                long left = match(n.id(), n.side(), n.type() == OrderType.MARKET ? null : n.price(), n.qty());
                if (left > 0 && n.type() == OrderType.LIMIT) {
                    list.add(new R(n.id(), n.side(), n.price(), left, seq++));
                }
            }
            case Command.Cancel x -> {
                if (!list.removeIf(r -> r.id.equals(x.id()))) {
                    throw new IllegalStateException("nothing to cancel: " + x.id());
                }
            }
            case Command.Replace r -> {
                R cur = list.stream().filter(e -> e.id.equals(r.id())).findFirst().orElseThrow();
                if (cur.price == r.newPrice() && r.newQty() <= cur.qty) {
                    cur.qty = r.newQty();
                    return;
                }
                list.remove(cur);
                long left = match(cur.id, cur.side, r.newPrice(), r.newQty());
                if (left > 0) {
                    list.add(new R(cur.id, cur.side, r.newPrice(), left, seq++));
                }
            }
        }
    }

    /** Total resting quantity on the other side that a limit order at {@code price} could trade with. */
    private long crossingQty(Side side, long price) {
        long sum = 0;
        for (R r : list) {
            if (r.side != side && crosses(side, price, r.price)) {
                sum += r.qty;
            }
        }
        return sum;
    }

    /** True when a taker at takerPrice can trade with a maker at makerPrice. */
    private static boolean crosses(Side taker, long takerPrice, long makerPrice) {
        return taker == Side.BUY ? makerPrice <= takerPrice : makerPrice >= takerPrice;
    }

    /** Repeatedly scans for the best crossing maker and trades at its price. A null limit means a market order. Returns the unfilled quantity. */
    private long match(String id, Side side, Long limit, long qty) {
        long left = qty;
        while (left > 0) {
            R best = null;
            for (R r : list) {
                if (r.side == side || (limit != null && !crosses(side, limit, r.price))) {
                    continue;
                }
                if (best == null || better(side, r, best)) {
                    best = r;
                }
            }
            if (best == null) {
                break;
            }
            long q = Math.min(left, best.qty);
            trades.add(new Trade(tradeSeq++, id, best.id, side, best.price, q));
            left -= q;
            best.qty -= q;
            if (best.qty == 0) {
                list.remove(best);
            }
        }
        return left;
    }

    /** True when a is a better maker than b for a taker of this side: lower price for a buyer, higher for a seller, then earlier. */
    private static boolean better(Side taker, R a, R b) {
        if (a.price != b.price) {
            return taker == Side.BUY ? a.price < b.price : a.price > b.price;
        }
        return a.seq < b.seq;
    }

    /** Renders the book in the same text format as {@link Book#snapshot()}. */
    String snapshot() {
        StringBuilder sb = new StringBuilder("BIDS\n");
        side(sb, Side.BUY);
        sb.append("ASKS\n");
        side(sb, Side.SELL);
        return sb.toString();
    }

    /** Writes one side: sorted best price first then by arrival, one line per level. */
    private void side(StringBuilder sb, Side s) {
        List<R> sorted = new ArrayList<>();
        for (R r : list) {
            if (r.side == s) {
                sorted.add(r);
            }
        }
        sorted.sort((a, b) -> a.price != b.price ? (s == Side.BUY ? Long.compare(b.price, a.price) : Long.compare(a.price, b.price)) : Long.compare(a.seq, b.seq));
        long cur = -1;
        for (R r : sorted) {
            if (r.price != cur) {
                if (cur != -1) {
                    sb.append('\n');
                }
                sb.append(r.price);
                cur = r.price;
            }
            sb.append(' ').append(r.id).append(':').append(r.qty);
        }
        if (cur != -1) {
            sb.append('\n');
        }
    }
}
