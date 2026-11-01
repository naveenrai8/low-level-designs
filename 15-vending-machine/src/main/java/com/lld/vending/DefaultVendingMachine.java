package com.lld.vending;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The machine itself. One lock guards all state, so a call is atomic and the machine is safe if two threads press buttons at once.
 *
 * <p>Two piles of coins are kept apart on purpose. The coin box holds the machine's own float. The session holds the coins the current customer inserted. A cancel or a failed sale hands back exactly the session coins, so "refund everything" can never fail. A successful sale moves the session into the box and then pays the change out of the box.
 *
 * <p>The lock is reentrant so a {@link Dispenser} test probe can call back into the machine on the same thread and see it refuse, because the state is DISPENSING.
 */
public final class DefaultVendingMachine implements VendingMachine {

    private final Map<String, Product> catalog = new LinkedHashMap<>();
    private final Set<Long> acceptedCoins;
    private final Inventory inventory;
    private final Dispenser dispenser;
    private final Telemetry telemetry;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<Long, Integer> box = new TreeMap<>();
    private final Map<Long, Integer> session = new TreeMap<>();
    private State state = State.IDLE;
    private Product selected;
    private long balance;

    /** Wires the machine. All dependencies are required; pass {@link Telemetry#none()} when there is no monitoring. */
    public DefaultVendingMachine(Collection<Product> products, Set<Long> acceptedCoins, Inventory inventory, Dispenser dispenser, Telemetry telemetry, Clock clock) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(dispenser, "dispenser");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(clock, "clock");
        for (Product p : products) {
            catalog.put(p.code(), p);
        }
        for (long c : acceptedCoins) {
            if (c <= 0) {
                throw new IllegalArgumentException("coin value must be above zero: " + c);
            }
        }
        this.acceptedCoins = Set.copyOf(acceptedCoins);
        this.inventory = inventory;
        this.dispenser = dispenser;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /** Returns the current state. */
    @Override
    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the session balance in minor units. */
    @Override
    public long balance() {
        lock.lock();
        try {
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /** Checks the transition table and throws without changing anything when the event is not allowed now. */
    void require(Event event) {
        if (!TransitionTable.isLegal(state, event)) {
            throw new IllegalTransitionException(state, event);
        }
    }

    /** Moves to the new state, but only along an edge the table lists. A bug here would be ours, so it fails loudly. */
    private void moveTo(Event event, State next) {
        if (!TransitionTable.targets(state, event).contains(next)) {
            throw new IllegalStateException("table does not allow " + state + " --" + event + "--> " + next);
        }
        state = next;
    }

    /** Starts a session for the chosen product. */
    @Override
    public void select(String code) {
        lock.lock();
        try {
            require(Event.SELECT);
            Product p = catalog.get(code);
            if (p == null) {
                throw new IllegalArgumentException("unknown product: " + code);
            }
            if (!inventory.inStock(code)) {
                throw new SoldOutException(code);
            }
            selected = p;
            moveTo(Event.SELECT, State.SELECTING);
        } finally {
            lock.unlock();
        }
    }

    /** Takes one coin. Becomes PAID once the balance reaches the price. */
    @Override
    public long insert(long coin) {
        lock.lock();
        try {
            require(Event.INSERT);
            if (!acceptedCoins.contains(coin)) {
                throw new IllegalArgumentException("coin not accepted: " + coin);
            }
            session.merge(coin, 1, Integer::sum);
            balance += coin;
            moveTo(Event.INSERT, balance >= selected.price() ? State.PAID : State.SELECTING);
            return balance;
        } finally {
            lock.unlock();
        }
    }

    /** Hands back the exact coins the customer inserted. */
    @Override
    public Payout cancel() {
        lock.lock();
        try {
            require(Event.CANCEL);
            Payout refund = new Payout(session);
            endSession(Event.CANCEL);
            return refund;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs the sale: check change and stock first, then dispense, then move the money.
     *
     * <p>Fault policy. Before the motor runs, no exact change or no stock means a full refund. During the motor, any failure (a jam, a runtime exception, even an Error) counts as "the product did not come out" and means a full refund. The cost: a fault that happened after the product really dropped would refund too, so the machine can lose one product. We accept that, because a customer who paid and got nothing is worse than a lost snack.
     *
     * <p>After the product dropped the sale stands, whatever happens to the paperwork. If the stock bookkeeping then throws, the money is still kept, the change is still paid and the session still ends, so the machine can never stay stuck in DISPENSING.
     */
    @Override
    public SaleResult dispense() {
        lock.lock();
        try {
            require(Event.DISPENSE);
            Product product = selected;
            if (!inventory.inStock(product.code())) {
                return refuse(SaleResult.Status.REFUSED_OUT_OF_STOCK, MachineEvent.Kind.REFUSED_OUT_OF_STOCK, product);
            }
            Map<Long, Integer> pool = merged(box, session);
            Optional<Map<Long, Integer>> change = ChangeMaker.make(balance - product.price(), pool);
            if (change.isEmpty()) {
                return refuse(SaleResult.Status.REFUSED_NO_CHANGE, MachineEvent.Kind.REFUSED_NO_CHANGE, product);
            }
            moveTo(Event.DISPENSE, State.DISPENSING);
            try {
                dispenser.dispense(product);
            } catch (DispenseJamException | RuntimeException | Error jam) {
                // any fault counts as a jam: the product did not come out
                Payout refund = new Payout(session);
                long refunded = refund.total();
                endSession(Event.JAM);
                report(MachineEvent.Kind.JAM, product.code(), refunded);
                return new SaleResult(SaleResult.Status.JAMMED_REFUNDED, null, refund, 0);
            }
            return settle(product, change.get());
        } finally {
            lock.unlock();
        }
    }

    /** Ends the session with a full refund and no sale, used before the motor ever runs. */
    private SaleResult refuse(SaleResult.Status status, MachineEvent.Kind kind, Product product) {
        Payout refund = new Payout(session);
        long refunded = refund.total();
        endSession(Event.DISPENSE);
        report(kind, product.code(), refunded);
        return new SaleResult(status, null, refund, 0);
    }

    /** Finishes a sale after the product dropped: the box gains the session coins and loses the change, then stock is reduced. The session always ends, even if the stock update throws. */
    private SaleResult settle(Product product, Map<Long, Integer> change) {
        Payout changePayout = new Payout(change);
        try {
            session.forEach((coin, n) -> box.merge(coin, n, Integer::sum));
            change.forEach((coin, n) -> box.merge(coin, -n, Integer::sum));
            try {
                inventory.take(product.code());
            } catch (RuntimeException bookkeeping) {
                // the product already dropped, so the sale stands; only the stock count is wrong
                report(MachineEvent.Kind.STOCK_FAULT, product.code(), 0);
            }
        } finally {
            endSession(Event.COMPLETE);
        }
        report(MachineEvent.Kind.SALE, product.code(), product.price());
        return new SaleResult(SaleResult.Status.SOLD, product.code(), changePayout, product.price());
    }

    /** Adds coins to the float. */
    @Override
    public void loadCoins(long coin, int count) {
        lock.lock();
        try {
            if (!acceptedCoins.contains(coin) || count < 0) {
                throw new IllegalArgumentException("bad coin or count: " + coin + " x " + count);
            }
            box.merge(coin, count, Integer::sum);
        } finally {
            lock.unlock();
        }
    }

    /** Returns a snapshot of the float. */
    @Override
    public Payout coinBox() {
        lock.lock();
        try {
            return new Payout(box);
        } finally {
            lock.unlock();
        }
    }

    /** Clears the session and goes back to IDLE through the table. */
    private void endSession(Event event) {
        session.clear();
        balance = 0;
        selected = null;
        moveTo(event, State.IDLE);
    }

    /** Returns a new map holding the sum of two coin piles. */
    private static Map<Long, Integer> merged(Map<Long, Integer> a, Map<Long, Integer> b) {
        Map<Long, Integer> out = new HashMap<>(a);
        b.forEach((coin, n) -> out.merge(coin, n, Integer::sum));
        return out;
    }

    /** Sends a telemetry event; swallows failures because monitoring must never break a sale or a refund. */
    private void report(MachineEvent.Kind kind, String code, long amount) {
        try {
            telemetry.emit(new MachineEvent(clock.instant(), kind, code, amount));
        } catch (RuntimeException ignored) {
            // a broken network is not the customer's problem
        }
    }
}
