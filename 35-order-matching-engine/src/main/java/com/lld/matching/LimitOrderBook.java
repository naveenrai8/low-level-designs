package com.lld.matching;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The order book for one instrument. Not thread-safe: exactly one thread may call it (see {@link MatchingEngine}).
 * <p>Data structure: each side is a {@link TreeMap} from price to a price level. A level is a {@link LinkedHashMap} from order id to order, so it iterates in arrival order (FIFO) and also removes by id in O(1). A second map holds every order ever accepted, by id.
 * <p>Costs, with L = price levels on the side, k = orders touched by one command: new resting order O(log L); best price O(log L) (first key of a TreeMap); a sweep O(k + levels emptied * log L); cancel O(log L) (level lookup plus an O(1) hash removal); duplicate-id check O(1); FOK pre-check O(k).
 * <p>There is no wall clock anywhere. Priority inside a level is the insertion order, so replaying the same commands gives the same book.
 */
public final class LimitOrderBook implements Book {

    /** The mutable inside of one order. Never handed out: callers get {@link OrderView}. */
    private static final class Order {
        final String id;
        final Side side;
        long price;
        long original;
        long filled;
        long resting;
        long cancelled;
        long seq;
        boolean live;

        /** Creates an order whose whole quantity is still unfilled and which is not yet in the book. */
        Order(String id, Side side, long price, long qty) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.original = qty;
            this.resting = qty;
        }

        /** Returns an immutable copy of the current state. */
        OrderView view() {
            return new OrderView(id, side, price, original, filled, resting, cancelled, seq);
        }
    }

    private final TreeMap<Long, LinkedHashMap<String, Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, LinkedHashMap<String, Order>> asks = new TreeMap<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private final List<Command> log = new ArrayList<>();
    private long nextSeq = 1;
    private long nextTradeSeq = 1;

    /** Creates an empty book. */
    public LimitOrderBook() {
    }

    /** Runs one command against the book, logs it if accepted, and returns what happened. */
    @Override
    public Result apply(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is null");
        }
        Result result = switch (command) {
            case Command.NewOrder n -> newOrder(n);
            case Command.Cancel c -> cancel(c);
            case Command.Replace r -> replace(r);
        };
        log.add(command);
        return result;
    }

    /** Validates, then matches a new order against the other side and rests or cancels what is left. Nothing is written until validation has passed. */
    private Result newOrder(Command.NewOrder n) {
        if (n.id() == null || n.id().isBlank()) {
            throw new IllegalArgumentException("id is blank");
        }
        if (n.side() == null || n.type() == null) {
            throw new IllegalArgumentException("side and type are required");
        }
        if (n.qty() <= 0) {
            throw new IllegalArgumentException("quantity must be above zero");
        }
        if (n.type() == OrderType.MARKET) {
            if (n.price() != 0) {
                throw new IllegalArgumentException("a market order has no price");
            }
        } else {
            checkPriceTimesQty(n.price(), n.qty());
        }
        if (orders.containsKey(n.id())) {
            throw new IllegalArgumentException("duplicate order id " + n.id());
        }
        Order o = new Order(n.id(), n.side(), n.price(), n.qty());
        boolean bounded = n.type() != OrderType.MARKET;
        boolean killed = n.type() == OrderType.FOK && fillable(o) < o.resting;
        orders.put(o.id, o);
        if (killed) {
            o.cancelled = o.resting;
            o.resting = 0;
            return new Result(o.id, Result.Outcome.KILLED, List.of(), 0, o.cancelled);
        }
        List<Trade> made = match(o, bounded);
        long cancelledNow = 0;
        if (o.resting > 0) {
            if (n.type() == OrderType.LIMIT) {
                rest(o);
            } else {
                cancelledNow = o.resting;
                o.cancelled += o.resting;
                o.resting = 0;
            }
        }
        return new Result(o.id, outcome(o, cancelledNow), made, o.resting, cancelledNow);
    }

    /** Removes the remaining quantity of a resting order. */
    private Result cancel(Command.Cancel c) {
        Order o = liveOrder(c.id());
        removeFromBook(o);
        long gone = o.resting;
        o.cancelled += gone;
        o.resting = 0;
        return new Result(o.id, Result.Outcome.CANCELLED, List.of(), 0, gone);
    }

    /** Changes price and/or quantity. Keeps the queue place only for the same price and a quantity that does not grow. */
    private Result replace(Command.Replace r) {
        Order o = liveOrder(r.id());
        if (r.newPrice() <= 0 || r.newQty() <= 0) {
            throw new IllegalArgumentException("price and quantity must be above zero (use cancel to remove)");
        }
        checkPriceTimesQty(r.newPrice(), r.newQty());
        long growth = Math.max(0, r.newQty() - o.resting);
        long newOriginal;
        try {
            newOriginal = Math.addExact(o.original, growth);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("replace would overflow the order's total quantity", e);
        }
        long shrink = Math.max(0, o.resting - r.newQty());
        if (r.newPrice() == o.price && growth == 0) {
            o.resting = r.newQty();
            o.cancelled += shrink;
            return new Result(o.id, Result.Outcome.RESTED, List.of(), o.resting, shrink);
        }
        removeFromBook(o);
        o.original = newOriginal;
        o.cancelled += shrink;
        o.resting = r.newQty();
        o.price = r.newPrice();
        List<Trade> made = match(o, true);
        if (o.resting > 0) {
            rest(o);
        }
        return new Result(o.id, outcome(o, 0), made, o.resting, shrink);
    }

    /** Returns the summary word for an order after a command. */
    private static Result.Outcome outcome(Order o, long cancelledNow) {
        if (o.resting > 0) {
            return Result.Outcome.RESTED;
        }
        return cancelledNow > 0 ? Result.Outcome.CANCELLED : Result.Outcome.FILLED;
    }

    /** Looks up an order that is waiting in the book, or throws {@link IllegalArgumentException}. */
    private Order liveOrder(String id) {
        Order o = id == null ? null : orders.get(id);
        if (o == null || !o.live) {
            throw new IllegalArgumentException("no resting order " + id);
        }
        return o;
    }

    /** Rejects a price or quantity whose product does not fit in a long, so later money maths cannot wrap. */
    private static void checkPriceTimesQty(long price, long qty) {
        if (price <= 0) {
            throw new IllegalArgumentException("price must be above zero");
        }
        try {
            Math.multiplyExact(price, qty);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("price times quantity overflows", e);
        }
    }

    /** Returns the side map an order of this side lives in. */
    private TreeMap<Long, LinkedHashMap<String, Order>> sideMap(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** True when a taker at {@code takerPrice} can trade with a maker at {@code makerPrice}: a buyer pays up to its price, a seller accepts down to its price. */
    private static boolean crosses(Side takerSide, long takerPrice, long makerPrice) {
        return takerSide == Side.BUY ? makerPrice <= takerPrice : makerPrice >= takerPrice;
    }

    /** Puts the order at the back of its price level and gives it a fresh queue stamp. */
    private void rest(Order o) {
        o.seq = nextSeq++;
        o.live = true;
        sideMap(o.side).computeIfAbsent(o.price, p -> new LinkedHashMap<>()).put(o.id, o);
    }

    /** Takes a live order out of its level (and drops the level if it is now empty). */
    private void removeFromBook(Order o) {
        TreeMap<Long, LinkedHashMap<String, Order>> map = sideMap(o.side);
        LinkedHashMap<String, Order> level = map.get(o.price);
        level.remove(o.id);
        if (level.isEmpty()) {
            map.remove(o.price);
        }
        o.live = false;
    }

    /** How much of the order could trade right now, capped at what it wants. Reads only, never changes the book, never overflows (it stops as soon as the order is covered, so the running total stays below the quantity wanted). */
    private long fillable(Order t) {
        long need = t.resting;
        long total = 0;
        for (Map.Entry<Long, LinkedHashMap<String, Order>> e : sideMap(t.side.opposite()).entrySet()) {
            if (!crosses(t.side, t.price, e.getKey())) {
                break;
            }
            for (Order m : e.getValue().values()) {
                if (m.resting >= need - total) {
                    return need;
                }
                total += m.resting;
            }
        }
        return total;
    }

    /** Sweeps the other side from the best price while the taker still has quantity and (if bounded) the price crosses. Every trade is at the maker's price. */
    private List<Trade> match(Order t, boolean bounded) {
        TreeMap<Long, LinkedHashMap<String, Order>> opposite = sideMap(t.side.opposite());
        List<Trade> made = new ArrayList<>();
        while (t.resting > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, LinkedHashMap<String, Order>> best = opposite.firstEntry();
            long makerPrice = best.getKey();
            if (bounded && !crosses(t.side, t.price, makerPrice)) {
                break;
            }
            LinkedHashMap<String, Order> level = best.getValue();
            Iterator<Order> it = level.values().iterator();
            while (it.hasNext() && t.resting > 0) {
                Order m = it.next();
                long q = Math.min(t.resting, m.resting);
                t.resting -= q;
                t.filled += q;
                m.resting -= q;
                m.filled += q;
                Trade trade = new Trade(nextTradeSeq++, t.id, m.id, t.side, makerPrice, q);
                trades.add(trade);
                made.add(trade);
                if (m.resting == 0) {
                    it.remove();
                    m.live = false;
                }
            }
            if (level.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }
        return made;
    }

    /** Returns the resting orders of a side in priority order (best price, then oldest). */
    @Override
    public List<OrderView> resting(Side side) {
        List<OrderView> out = new ArrayList<>();
        for (LinkedHashMap<String, Order> level : sideMap(side).values()) {
            for (Order o : level.values()) {
                out.add(o.view());
            }
        }
        return out;
    }

    /** Returns a view of every order ever accepted, in acceptance order. */
    @Override
    public List<OrderView> orders() {
        List<OrderView> out = new ArrayList<>();
        for (Order o : orders.values()) {
            out.add(o.view());
        }
        return out;
    }

    /** Returns a copy of the trade list, oldest first. */
    @Override
    public List<Trade> trades() {
        return Collections.unmodifiableList(new ArrayList<>(trades));
    }

    /** Returns a copy of the accepted-command log, oldest first. */
    @Override
    public List<Command> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    /** Returns the best (highest) bid price, or -1 when there are no bids. */
    public long bestBid() {
        return bids.isEmpty() ? -1 : bids.firstKey();
    }

    /** Returns the best (lowest) ask price, or -1 when there are no asks. */
    public long bestAsk() {
        return asks.isEmpty() ? -1 : asks.firstKey();
    }

    /**
     * Checks every invariant and throws {@link IllegalStateException} naming the first one broken: never crossed, no empty level and no zero-quantity resting order, the live flag of every order matches whether it really sits in a level, FIFO stamps increase inside a level, per-order conservation, global conservation (also against the trade list).
     */
    public void checkInvariants() {
        if (!bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey()) {
            throw new IllegalStateException("book is crossed: bid " + bids.firstKey() + " ask " + asks.firstKey());
        }
        for (Side side : Side.values()) {
            for (Map.Entry<Long, LinkedHashMap<String, Order>> e : sideMap(side).entrySet()) {
                if (e.getValue().isEmpty()) {
                    throw new IllegalStateException("empty level " + e.getKey());
                }
                long lastSeq = 0;
                for (Order o : e.getValue().values()) {
                    if (o.resting <= 0 || !o.live || o.price != e.getKey()) {
                        throw new IllegalStateException("bad resting order " + o.id);
                    }
                    if (o.seq <= lastSeq) {
                        throw new IllegalStateException("FIFO broken at " + o.id);
                    }
                    lastSeq = o.seq;
                }
            }
        }
        BigInteger original = BigInteger.ZERO;
        BigInteger rest = BigInteger.ZERO;
        BigInteger filledBuy = BigInteger.ZERO;
        BigInteger filledSell = BigInteger.ZERO;
        BigInteger cancelled = BigInteger.ZERO;
        for (Order o : orders.values()) {
            if (BigInteger.valueOf(o.original).compareTo(BigInteger.valueOf(o.filled).add(BigInteger.valueOf(o.resting)).add(BigInteger.valueOf(o.cancelled))) != 0) {
                throw new IllegalStateException("conservation broken for " + o.id);
            }
            LinkedHashMap<String, Order> level = sideMap(o.side).get(o.price);
            boolean inBook = o.resting > 0 && level != null && level.get(o.id) == o;
            if (o.live != inBook) {
                throw new IllegalStateException("live flag wrong for " + o.id);
            }
            original = original.add(BigInteger.valueOf(o.original));
            rest = rest.add(BigInteger.valueOf(o.resting));
            cancelled = cancelled.add(BigInteger.valueOf(o.cancelled));
            if (o.side == Side.BUY) {
                filledBuy = filledBuy.add(BigInteger.valueOf(o.filled));
            } else {
                filledSell = filledSell.add(BigInteger.valueOf(o.filled));
            }
        }
        BigInteger traded = BigInteger.ZERO;
        for (Trade t : trades) {
            traded = traded.add(BigInteger.valueOf(t.qty()));
        }
        if (!filledBuy.equals(filledSell) || !filledBuy.equals(traded) || !original.equals(filledBuy.add(filledSell).add(rest).add(cancelled))) {
            throw new IllegalStateException("global conservation broken");
        }
    }
}
