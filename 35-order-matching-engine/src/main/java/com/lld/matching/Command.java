package com.lld.matching;

/**
 * Everything a caller can ask the book to do. Commands are immutable values, so a list of them is a complete, replayable history.
 * <p>Error contract (all {@link IllegalArgumentException}, and a rejected command changes nothing): a blank id, a null side or type, quantity or price not above zero, a market order that carries a price, price times quantity that overflows a long, a duplicate order id, and cancel or replace of an id that is not resting.
 */
public sealed interface Command {

    /**
     * Submits a new order. Price and quantity are whole numbers (price in ticks), never doubles. A market order must use price 0.
     *
     * @param id   unique for the life of the book, even after the order is gone
     * @param side buy or sell
     * @param type limit, IOC, FOK or market
     * @param price limit price in ticks (0 for market)
     * @param qty  quantity in whole units
     */
    record NewOrder(String id, Side side, OrderType type, long price, long qty) implements Command {
        /** Builds a good-till-cancelled limit order. */
        public static NewOrder limit(String id, Side side, long price, long qty) {
            return new NewOrder(id, side, OrderType.LIMIT, price, qty);
        }

        /** Builds an immediate-or-cancel limit order. */
        public static NewOrder ioc(String id, Side side, long price, long qty) {
            return new NewOrder(id, side, OrderType.IOC, price, qty);
        }

        /** Builds a fill-or-kill limit order. */
        public static NewOrder fok(String id, Side side, long price, long qty) {
            return new NewOrder(id, side, OrderType.FOK, price, qty);
        }

        /** Builds a market order (price 0). */
        public static NewOrder market(String id, Side side, long qty) {
            return new NewOrder(id, side, OrderType.MARKET, 0, qty);
        }
    }

    /**
     * Removes the whole remaining quantity of a resting order.
     *
     * @param id the resting order
     */
    record Cancel(String id) implements Command {
    }

    /**
     * Changes a resting order. Same price and a smaller or equal quantity keeps its place in the queue. A new price or a bigger quantity sends it to the back of the queue (and it may trade at once if the new price crosses).
     *
     * @param id       the resting order
     * @param newPrice the new limit price in ticks
     * @param newQty   the new quantity still wanted (what is left, not the original size)
     */
    record Replace(String id, long newPrice, long newQty) implements Command {
    }
}
