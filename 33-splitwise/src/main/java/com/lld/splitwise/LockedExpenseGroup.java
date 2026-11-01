package com.lld.splitwise;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The group implementation. One {@link ReentrantLock} per group guards the history and the running balances, so a group is always consistent and different groups never wait for each other.
 */
public final class LockedExpenseGroup implements ExpenseGroup {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final Set<String> members;
    private final Clock clock;
    private final FxRates rates;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Entry> history = new ArrayList<>();
    private final Map<String, Map<String, Long>> running = new TreeMap<>();

    /**
     * Creates a group.
     *
     * @param members the people in the group, at least one, none null
     * @param clock source of the time stamped on history lines (inject a fixed one in tests)
     * @param rates exchange rates used only by {@link #settleUpIn}
     * @throws IllegalArgumentException if the member set is empty or contains null
     */
    public LockedExpenseGroup(Set<String> members, Clock clock, FxRates rates) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("a group needs members");
        }
        this.members = new TreeSet<>();
        for (String m : members) {
            if (m == null) {
                throw new IllegalArgumentException("a member must not be null");
            }
            this.members.add(m);
        }
        this.clock = clock;
        this.rates = rates;
    }

    /** {@inheritDoc} */
    @Override
    public ExpenseEntry addExpense(String payer, String currency, long total, SplitStrategy strategy, Map<String, Long> input) {
        checkMember(payer);
        checkCurrency(currency);
        if (total <= 0 || strategy == null || input == null) {
            throw new IllegalArgumentException("an expense needs a positive total, a strategy and an input");
        }
        // Copy first, so the caller cannot change the map between our checks and the strategy using it.
        Map<String, Long> copy = new TreeMap<>();
        for (Map.Entry<String, Long> e : input.entrySet()) {
            checkMember(e.getKey());
            if (e.getValue() == null) {
                throw new IllegalArgumentException("null value for " + e.getKey());
            }
            copy.put(e.getKey(), e.getValue());
        }
        // Split outside the lock and before any state change: if anything throws, nothing was written.
        Map<String, Long> shares = checkedShares(total, strategy.split(total, copy));
        lock.lock();
        try {
            Map<String, Long> next = new TreeMap<>(running.getOrDefault(currency, Map.of()));
            ExpenseEntry e = new ExpenseEntry(history.size() + 1L, clock.instant(), payer, currency, total, shares);
            apply(next, e);
            history.add(e);
            running.put(currency, next);
            return e;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Does not trust the strategy: shares must belong to members, be non-negative and add up to the total exactly.
     *
     * @throws IllegalArgumentException if any of those is broken, or the strategy returned null
     */
    private Map<String, Long> checkedShares(long total, Map<String, Long> shares) {
        if (shares == null) {
            throw new IllegalArgumentException("strategy returned no shares");
        }
        long sum = 0;
        Map<String, Long> copy = new TreeMap<>();
        for (Map.Entry<String, Long> e : shares.entrySet()) {
            checkMember(e.getKey());
            if (e.getValue() == null || e.getValue() < 0) {
                throw new IllegalArgumentException("strategy returned a bad share for " + e.getKey());
            }
            sum = Math.addExact(sum, e.getValue());
            copy.put(e.getKey(), e.getValue());
        }
        if (sum != total) {
            throw new IllegalArgumentException("strategy shares add up to " + sum + " but the total is " + total);
        }
        return copy;
    }

    /** {@inheritDoc} */
    @Override
    public PaymentEntry recordPayment(String from, String to, String currency, long amount) {
        checkMember(from);
        checkMember(to);
        checkCurrency(currency);
        if (amount <= 0 || from.equals(to)) {
            throw new IllegalArgumentException("a payment needs a positive amount between two different people");
        }
        lock.lock();
        try {
            Map<String, Long> next = new TreeMap<>(running.getOrDefault(currency, Map.of()));
            PaymentEntry p = new PaymentEntry(history.size() + 1L, clock.instant(), from, to, currency, amount);
            apply(next, p);
            history.add(p);
            running.put(currency, next);
            return p;
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Long> nets(String currency) {
        checkCurrency(currency);
        lock.lock();
        try {
            return full(running.get(currency));
        } finally {
            lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Transfer> pairwise(String currency) {
        checkCurrency(currency);
        return pairwise(history(), currency);
    }

    /** {@inheritDoc} */
    @Override
    public List<Transfer> simplify(String currency) {
        return Debts.greedy(nets(currency));
    }

    /** {@inheritDoc} */
    @Override
    public List<Transfer> settleUpIn(String target) {
        checkCurrency(target);
        Map<String, Long> total = new TreeMap<>();
        lock.lock();
        try {
            for (String m : members) {
                total.put(m, 0L);
            }
            for (Map.Entry<String, Map<String, Long>> c : running.entrySet()) {
                Map<String, Long> converted = Fx.convert(full(c.getValue()), rates.micros(c.getKey(), target));
                converted.forEach((user, v) -> total.merge(user, v, Math::addExact));
            }
        } finally {
            lock.unlock();
        }
        return Debts.greedy(total);
    }

    /** {@inheritDoc} */
    @Override
    public List<Entry> history() {
        lock.lock();
        try {
            return List.copyOf(history);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Rebuilds the nets of every currency from nothing but a history (no running state). The differential test compares this to the running balances.
     *
     * @return currency to (user to net)
     */
    public static Map<String, Map<String, Long>> rebuild(List<Entry> entries) {
        Map<String, Map<String, Long>> out = new TreeMap<>();
        for (Entry e : entries) {
            apply(out.computeIfAbsent(currencyOf(e), k -> new TreeMap<>()), e);
        }
        return out;
    }

    /** The members, sorted. */
    public Set<String> members() {
        return Collections.unmodifiableSet(members);
    }

    /**
     * Derives who-owes-whom from a history: every share is a debt from the sharer to the payer, every payment cancels debt the other way, and each pair is netted into one direction.
     */
    static List<Transfer> pairwise(List<Entry> entries, String currency) {
        Map<String, Long> pair = new TreeMap<>();
        for (Entry e : entries) {
            if (e instanceof ExpenseEntry x && x.currency().equals(currency)) {
                x.shares().forEach((user, share) -> addPair(pair, user, x.payer(), share));
            } else if (e instanceof PaymentEntry p && p.currency().equals(currency)) {
                addPair(pair, p.from(), p.to(), -p.amount());
            }
        }
        List<Transfer> out = new ArrayList<>();
        pair.forEach((key, v) -> {
            if (v != 0) {
                String[] ab = key.split("\\|");
                out.add(v > 0 ? new Transfer(ab[0], ab[1], v) : new Transfer(ab[1], ab[0], -v));
            }
        });
        return out;
    }

    /** Adds {@code amount} to "debtor owes creditor", stored under the sorted pair key with the sign flipped when the order is swapped. */
    private static void addPair(Map<String, Long> pair, String debtor, String creditor, long amount) {
        if (debtor.equals(creditor)) {
            return;
        }
        if (debtor.compareTo(creditor) < 0) {
            pair.merge(debtor + "|" + creditor, amount, Long::sum);
        } else {
            pair.merge(creditor + "|" + debtor, -amount, Long::sum);
        }
    }

    /** The currency a history line is in. */
    private static String currencyOf(Entry e) {
        return e instanceof ExpenseEntry x ? x.currency() : ((PaymentEntry) e).currency();
    }

    /**
     * Applies one history line to ONE currency's nets: payer is credited the total and each sharer debited their share; a payment credits the payer and debits the receiver.
     *
     * @throws ArithmeticException if a balance would overflow a long (the caller applies to a copy, so nothing is half-written)
     */
    private static void apply(Map<String, Long> nets, Entry e) {
        if (e instanceof ExpenseEntry x) {
            nets.merge(x.payer(), x.total(), Math::addExact);
            x.shares().forEach((user, share) -> nets.merge(user, -share, Math::addExact));
        } else if (e instanceof PaymentEntry p) {
            nets.merge(p.from(), p.amount(), Math::addExact);
            nets.merge(p.to(), -p.amount(), Math::addExact);
        }
    }

    /** Copies a nets table and adds a 0 for every member that has no entry yet. */
    private Map<String, Long> full(Map<String, Long> nets) {
        Map<String, Long> out = new TreeMap<>();
        for (String m : members) {
            out.put(m, nets == null ? 0L : nets.getOrDefault(m, 0L));
        }
        return out;
    }

    /** Rejects null and anyone who is not in the group. */
    private void checkMember(String user) {
        if (user == null || !members.contains(user)) {
            throw new IllegalArgumentException("not a member: " + user);
        }
    }

    /** Rejects anything but a three-capital-letter currency code. */
    private static void checkCurrency(String currency) {
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("bad currency code: " + currency);
        }
    }
}
