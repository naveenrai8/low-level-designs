package com.lld.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The in-memory ledger and hold service.
 *
 * <p>Concurrency model: each account has its own lock and its own running balance. An entry takes the locks of every account it touches in sorted account-id order (so two opposite transfers cannot deadlock), checks the funds, then appends to the journal and updates the balances while still holding them. There is no global lock; entries on disjoint accounts run in parallel. The only shared point is one atomic counter for the sequence number.
 *
 * <p>Finished holds stay in the hold table so a retried capture can be answered; production archives them after a retention period.
 *
 * <p>Idempotency keys are kept in a bounded map. After the bound is reached the oldest finished keys are forgotten, so a very late retry would post again. In production the key is stored with the entry in the same database transaction under a unique index, and never forgotten.
 */
public final class InMemoryLedger implements Ledger, HoldService {

    /** The default number of idempotency keys remembered. */
    public static final int DEFAULT_MAX_KEYS = 100_000;

    /** One account's mutable state; every field is guarded by {@code lock}. */
    private static final class AccountState {
        final Account account;
        final ReentrantLock lock = new ReentrantLock();
        long balance;
        long held;
        final Map<String, Hold> activeHolds = new LinkedHashMap<>();
        // pairs of {seq, balanceAfter}, in seq order, so balanceAt can binary search
        final List<long[]> history = new ArrayList<>();

        /**
         * Creates the state for a new account.
         *
         * @param account the account
         */
        AccountState(Account account) {
            this.account = account;
        }

        /**
         * Turns a signed balance into the balance on the account's normal side.
         *
         * @param signed debits minus credits
         * @return the natural balance
         */
        long natural(long signed) {
            return account.type().isDebitNormal() ? signed : -signed;
        }
    }

    /** One hold; {@code state} is guarded by the lock of its account. */
    private static final class Hold {
        final String id;
        final String accountId;
        final long amount;
        final Instant deadline;
        HoldState state = HoldState.AUTHORIZED;
        Entry capturedEntry;
        long capturedAmount;
        String capturedTo;

        /**
         * Creates an active hold.
         *
         * @param id the hold id
         * @param accountId the held account
         * @param amount the held amount
         * @param deadline when it expires, or null for never
         */
        Hold(String id, String accountId, long amount, Instant deadline) {
            this.id = id;
            this.accountId = accountId;
            this.amount = amount;
            this.deadline = deadline;
        }
    }

    /** One idempotency record: the draft the key first came with, and the entry it produced. Guarded by its own monitor. */
    private static final class Slot {
        final Draft draft;
        Entry entry;
        boolean dead;

        /**
         * Creates an empty slot for a first-seen key.
         *
         * @param draft the draft that came with the key
         */
        Slot(Draft draft) {
            this.draft = draft;
        }
    }

    private final Clock clock;
    private final int maxKeys;
    private final Gap gap;
    private final LockOrder lockOrder;
    private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Long, Entry> journal = new ConcurrentSkipListMap<>();
    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> reversed = new ConcurrentHashMap<>();
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> finishedKeys = new ConcurrentLinkedQueue<>();
    private final AtomicInteger finishedCount = new AtomicInteger();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong holdSeq = new AtomicLong();

    /**
     * Creates a ledger with the default idempotency bound.
     *
     * @param clock the time source, used only for hold expiry
     */
    public InMemoryLedger(Clock clock) {
        this(clock, DEFAULT_MAX_KEYS, Gap.NONE, LockOrder.SORTED);
    }

    /**
     * Creates a ledger with an explicit idempotency bound.
     *
     * @param clock the time source
     * @param maxKeys how many finished idempotency keys to remember
     */
    public InMemoryLedger(Clock clock, int maxKeys) {
        this(clock, maxKeys, Gap.NONE, LockOrder.SORTED);
    }

    /**
     * Full constructor for tests: adds the interleaving hook and the (possibly broken) lock order.
     *
     * @param clock the time source
     * @param maxKeys how many finished idempotency keys to remember
     * @param gap the hook called inside the critical section
     * @param lockOrder sorted (correct) or as written (deadlock-prone)
     */
    InMemoryLedger(Clock clock, int maxKeys, Gap gap, LockOrder lockOrder) {
        this.clock = clock;
        this.maxKeys = maxKeys;
        this.gap = gap;
        this.lockOrder = lockOrder;
    }

    /** Opens the account with a zero balance; a second open of the same id throws DUPLICATE_ACCOUNT. */
    @Override
    public void openAccount(Account account) {
        if (accounts.putIfAbsent(account.id(), new AccountState(account)) != null) {
            throw new LedgerException(LedgerException.Reason.DUPLICATE_ACCOUNT, account.id());
        }
    }

    /** Posts the draft once, with no retry protection (see the interface for the error contract). */
    @Override
    public Entry post(Draft draft) {
        return doPost(draft, 0, false);
    }

    /** Posts the draft once per key: a replay returns the first entry, and a different draft under the same key throws. */
    @Override
    public Entry post(Draft draft, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LedgerException(LedgerException.Reason.BAD_ARGUMENT, "idempotency key is blank");
        }
        while (true) {
            Slot slot = slots.computeIfAbsent(idempotencyKey, k -> new Slot(draft));
            synchronized (slot) {
                if (slot.dead) {
                    continue;
                }
                if (!slot.draft.equals(draft)) {
                    throw new LedgerException(LedgerException.Reason.IDEMPOTENCY_CONFLICT, "key " + idempotencyKey + " was used with a different entry");
                }
                if (slot.entry == null) {
                    try {
                        slot.entry = doPost(draft, 0, false);
                    } catch (RuntimeException e) {
                        // a failed post is not remembered, so the caller may retry with the same key
                        slot.dead = true;
                        slots.remove(idempotencyKey, slot);
                        throw e;
                    }
                    rememberFinished(idempotencyKey);
                }
                return slot.entry;
            }
        }
    }

    /**
     * Records that a key finished and forgets the oldest finished keys beyond the bound.
     *
     * @param key the finished key
     */
    private void rememberFinished(String key) {
        finishedKeys.add(key);
        finishedCount.incrementAndGet();
        while (finishedCount.get() > maxKeys) {
            String old = finishedKeys.poll();
            if (old == null) {
                break;
            }
            finishedCount.decrementAndGet();
            slots.remove(old);
        }
    }

    /** Appends the mirror-image entry; marks the original as reversed so it cannot be reversed twice. */
    @Override
    public Entry reverse(String entryId) {
        Entry original = entryId == null ? null : byId.get(entryId);
        if (original == null) {
            throw new LedgerException(LedgerException.Reason.UNKNOWN_ENTRY, String.valueOf(entryId));
        }
        if (reversed.putIfAbsent(original.seq(), Boolean.TRUE) != null) {
            throw new LedgerException(LedgerException.Reason.ALREADY_REVERSED, entryId);
        }
        try {
            return doPost(new Draft(original.postings(), "reversal of " + entryId).negated("reversal of " + entryId), original.seq(), true);
        } catch (RuntimeException e) {
            reversed.remove(original.seq());
            throw e;
        }
    }

    /**
     * The one place an entry is written. Validates the accounts, locks them, checks funds, then appends and updates balances.
     *
     * @param draft the balanced postings
     * @param reversesSeq the sequence of the entry being reversed, or 0
     * @param force true to skip the funds check (reversals)
     * @return the posted entry
     */
    private Entry doPost(Draft draft, long reversesSeq, boolean force) {
        Map<String, Long> delta = new LinkedHashMap<>();
        String currency = null;
        for (Posting p : draft.postings()) {
            AccountState st = state(p.accountId());
            if (currency == null) {
                currency = st.account.currency();
            } else if (!currency.equals(st.account.currency())) {
                throw new LedgerException(LedgerException.Reason.MIXED_CURRENCY, "an entry must use one currency; use an FX account pair per currency");
            }
            delta.merge(p.accountId(), p.amount(), Long::sum);
        }
        List<String> order = lockOrder == LockOrder.SORTED ? new ArrayList<>(new TreeSet<>(delta.keySet())) : new ArrayList<>(delta.keySet());
        List<ReentrantLock> taken = new ArrayList<>();
        try {
            for (String id : order) {
                ReentrantLock l = state(id).lock;
                try {
                    l.lockInterruptibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LedgerException(LedgerException.Reason.INTERRUPTED, "interrupted while waiting for " + id);
                }
                taken.add(l);
                gap.at("locked:" + id);
            }
            Map<String, Long> next = new HashMap<>();
            for (Map.Entry<String, Long> d : delta.entrySet()) {
                AccountState st = state(d.getKey());
                long after;
                try {
                    after = Math.addExact(st.balance, d.getValue());
                } catch (ArithmeticException e) {
                    throw new LedgerException(LedgerException.Reason.OVERFLOW, "balance of " + d.getKey() + " overflows");
                }
                next.put(d.getKey(), after);
                if (!force && !st.account.overdraftAllowed() && st.natural(after) < st.natural(st.balance)) {
                    expireDue(st);
                    if (st.natural(after) - st.held < 0) {
                        throw new LedgerException(LedgerException.Reason.INSUFFICIENT_FUNDS, d.getKey() + " has " + (st.natural(st.balance) - st.held) + " available");
                    }
                }
            }
            gap.at("checked");
            long n = seq.incrementAndGet();
            Entry entry = new Entry(n, "e" + n, currency, draft.postings(), draft.memo(), reversesSeq);
            for (Map.Entry<String, Long> a : next.entrySet()) {
                AccountState st = state(a.getKey());
                st.balance = a.getValue();
                st.history.add(new long[] {n, a.getValue()});
            }
            journal.put(n, entry);
            byId.put(entry.id(), entry);
            return entry;
        } finally {
            for (int i = taken.size() - 1; i >= 0; i--) {
                taken.get(i).unlock();
            }
        }
    }

    /**
     * Finds an account's state or throws UNKNOWN_ACCOUNT.
     *
     * @param id the account id
     * @return its state
     */
    private AccountState state(String id) {
        AccountState st = accounts.get(id);
        if (st == null) {
            throw new LedgerException(LedgerException.Reason.UNKNOWN_ACCOUNT, String.valueOf(id));
        }
        return st;
    }

    /**
     * Turns holds whose deadline has passed into EXPIRED and frees their money. The caller must hold the account lock.
     *
     * @param st the account state
     */
    private void expireDue(AccountState st) {
        Instant now = clock.instant();
        st.activeHolds.values().removeIf(h -> {
            boolean due = h.deadline != null && !now.isBefore(h.deadline);
            if (due) {
                h.state = HoldState.EXPIRED;
                st.held -= h.amount;
            }
            return due;
        });
    }

    /**
     * Test probe: says whether another thread is queued waiting for this account's lock, so a test can wait until a rival is really blocked.
     *
     * @param accountId the account
     * @return true when at least one thread waits for its lock; false for an unknown account
     */
    boolean hasWaiters(String accountId) {
        AccountState st = accounts.get(accountId);
        return st != null && st.lock.hasQueuedThreads();
    }

    /**
     * Locks every account of one currency (or all accounts when the currency is null) in sorted id order, so the caller sees no post half done.
     *
     * @param currency the currency to lock, or null for all
     * @return the locks taken, to be released by {@link #unlockAll}
     */
    private List<ReentrantLock> lockAll(String currency) {
        List<ReentrantLock> taken = new ArrayList<>();
        try {
            for (String id : new TreeSet<>(accounts.keySet())) {
                AccountState st = accounts.get(id);
                if (currency == null || st.account.currency().equals(currency)) {
                    lockOrThrow(st.lock, id);
                    taken.add(st.lock);
                }
            }
        } catch (RuntimeException e) {
            unlockAll(taken);
            throw e;
        }
        return taken;
    }

    /**
     * Releases locks taken by {@link #lockAll}, last taken first.
     *
     * @param taken the locks
     */
    private static void unlockAll(List<ReentrantLock> taken) {
        for (int i = taken.size() - 1; i >= 0; i--) {
            taken.get(i).unlock();
        }
    }

    /** Reads the signed running balance under the account lock. */
    @Override
    public long balance(String accountId) {
        AccountState st = state(accountId);
        st.lock.lock();
        try {
            return st.balance;
        } finally {
            st.lock.unlock();
        }
    }

    /** Reads the running balance on the account's normal side. */
    @Override
    public long naturalBalance(String accountId) {
        AccountState st = state(accountId);
        st.lock.lock();
        try {
            return st.natural(st.balance);
        } finally {
            st.lock.unlock();
        }
    }

    /** Reads the natural balance minus active holds, after expiring due holds. */
    @Override
    public long available(String accountId) {
        AccountState st = state(accountId);
        st.lock.lock();
        try {
            expireDue(st);
            return st.natural(st.balance) - st.held;
        } finally {
            st.lock.unlock();
        }
    }

    /** Binary searches the account's history for the balance after the given journal position. */
    @Override
    public long balanceAt(String accountId, long atSeq) {
        AccountState st = state(accountId);
        st.lock.lock();
        try {
            int lo = 0;
            int hi = st.history.size() - 1;
            long result = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (st.history.get(mid)[0] <= atSeq) {
                    result = st.history.get(mid)[1];
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return result;
        } finally {
            st.lock.unlock();
        }
    }

    /** Locks every account of the currency in sorted order, then adds up the signed balances; zero on every read, even while posts run. */
    @Override
    public long trialBalance(String currency) {
        List<ReentrantLock> taken = lockAll(currency);
        try {
            long sum = 0;
            for (AccountState st : accounts.values()) {
                if (st.account.currency().equals(currency)) {
                    sum += st.balance;
                }
            }
            return sum;
        } finally {
            unlockAll(taken);
        }
    }

    /** Locks all accounts in sorted order, then copies the journal in sequence order into a read-only list, so no post is half visible. */
    @Override
    public List<Entry> journal() {
        List<ReentrantLock> taken = lockAll(null);
        try {
            return Collections.unmodifiableList(new ArrayList<>(journal.values()));
        } finally {
            unlockAll(taken);
        }
    }

    /** Locks all accounts, then replays the whole journal to recompute every balance, ignoring the running balances. */
    @Override
    public Map<String, Long> rebuild() {
        List<ReentrantLock> taken = lockAll(null);
        try {
            Map<String, Long> out = new HashMap<>();
            for (String id : accounts.keySet()) {
                out.put(id, 0L);
            }
            for (Entry e : journal.values()) {
                for (Posting p : e.postings()) {
                    out.merge(p.accountId(), p.amount(), Long::sum);
                }
            }
            return out;
        } finally {
            unlockAll(taken);
        }
    }

    /** Reserves money with no time limit; a retry reserves again because authorize has no key. */
    @Override
    public String authorize(String accountId, long amount) {
        return authorizeUntil(accountId, amount, null);
    }

    /** Creates a hold after checking available funds under the account lock. */
    @Override
    public String authorize(String accountId, long amount, Duration ttl) {
        return authorizeUntil(accountId, amount, clock.instant().plus(ttl));
    }

    /**
     * Creates a hold if the account has enough available money.
     *
     * @param accountId the account
     * @param amount the amount to reserve
     * @param deadline when the hold expires, or null
     * @return the hold id
     */
    private String authorizeUntil(String accountId, long amount, Instant deadline) {
        if (amount <= 0) {
            throw new LedgerException(LedgerException.Reason.ZERO_AMOUNT, "hold amount must be positive");
        }
        AccountState st = state(accountId);
        st.lock.lock();
        try {
            expireDue(st);
            if (!st.account.overdraftAllowed() && st.natural(st.balance) - st.held < amount) {
                throw new LedgerException(LedgerException.Reason.INSUFFICIENT_FUNDS, accountId + " has " + (st.natural(st.balance) - st.held) + " available");
            }
            Hold h = new Hold("h" + holdSeq.incrementAndGet(), accountId, amount, deadline);
            st.held = Math.addExact(st.held, amount);
            st.activeHolds.put(h.id, h);
            holds.put(h.id, h);
            return h.id;
        } finally {
            st.lock.unlock();
        }
    }

    /** Locks both accounts in sorted order, finishes the hold, and posts the transfer as one entry; undoes the hold change if the post fails. */
    @Override
    public Entry capture(String holdId, long amount, String toAccountId) {
        Hold h = hold(holdId);
        if (amount <= 0) {
            throw new LedgerException(LedgerException.Reason.ZERO_AMOUNT, "capture amount must be positive");
        }
        if (h.accountId.equals(toAccountId)) {
            throw new LedgerException(LedgerException.Reason.BAD_ARGUMENT, "cannot capture into the held account");
        }
        AccountState from = state(h.accountId);
        state(toAccountId);
        List<ReentrantLock> taken = new ArrayList<>();
        try {
            for (String id : new TreeSet<>(List.of(h.accountId, toAccountId))) {
                ReentrantLock l = state(id).lock;
                lockOrThrow(l, id);
                taken.add(l);
                gap.at("locked:" + id);
            }
            expireDue(from);
            if (h.state == HoldState.EXPIRED) {
                throw new LedgerException(LedgerException.Reason.HOLD_EXPIRED, holdId);
            }
            if (h.state == HoldState.CAPTURED && h.capturedAmount == amount && h.capturedTo.equals(toAccountId)) {
                return h.capturedEntry;
            }
            if (h.state != HoldState.AUTHORIZED) {
                throw new LedgerException(LedgerException.Reason.HOLD_NOT_ACTIVE, holdId + " is " + h.state);
            }
            if (amount > h.amount) {
                throw new LedgerException(LedgerException.Reason.CAPTURE_TOO_LARGE, "capture " + amount + " > held " + h.amount);
            }
            // release the whole hold first, so the captured money is not blocked by its own hold; the rest stays released
            from.held -= h.amount;
            from.activeHolds.remove(h.id);
            h.state = HoldState.CAPTURED;
            long fromSign = from.account.type().isDebitNormal() ? Math.negateExact(amount) : amount;
            try {
                Entry done = doPost(Draft.of("capture " + holdId, new Posting(h.accountId, fromSign), new Posting(toAccountId, Math.negateExact(fromSign))), 0, false);
                h.capturedEntry = done;
                h.capturedAmount = amount;
                h.capturedTo = toAccountId;
                return done;
            } catch (LedgerException e) {
                from.held += h.amount;
                from.activeHolds.put(h.id, h);
                h.state = HoldState.AUTHORIZED;
                throw e;
            }
        } finally {
            for (int i = taken.size() - 1; i >= 0; i--) {
                taken.get(i).unlock();
            }
        }
    }

    /** Releases an active hold; any other state throws HOLD_NOT_ACTIVE or HOLD_EXPIRED. */
    @Override
    public void voidHold(String holdId) {
        Hold h = hold(holdId);
        AccountState st = state(h.accountId);
        st.lock.lock();
        try {
            expireDue(st);
            if (h.state == HoldState.EXPIRED) {
                throw new LedgerException(LedgerException.Reason.HOLD_EXPIRED, holdId);
            }
            if (h.state != HoldState.AUTHORIZED) {
                throw new LedgerException(LedgerException.Reason.HOLD_NOT_ACTIVE, holdId + " is " + h.state);
            }
            st.held -= h.amount;
            st.activeHolds.remove(h.id);
            h.state = HoldState.VOIDED;
        } finally {
            st.lock.unlock();
        }
    }

    /** Reads a hold's state after expiring any hold whose deadline has passed. */
    @Override
    public HoldState holdState(String holdId) {
        Hold h = hold(holdId);
        AccountState st = state(h.accountId);
        st.lock.lock();
        try {
            expireDue(st);
            return h.state;
        } finally {
            st.lock.unlock();
        }
    }

    /**
     * Finds a hold or throws UNKNOWN_HOLD.
     *
     * @param id the hold id
     * @return the hold
     */
    private Hold hold(String id) {
        Hold h = id == null ? null : holds.get(id);
        if (h == null) {
            throw new LedgerException(LedgerException.Reason.UNKNOWN_HOLD, String.valueOf(id));
        }
        return h;
    }

    /**
     * Takes a lock, but lets an interrupt break the wait; the interrupt flag is restored and INTERRUPTED is thrown.
     *
     * @param l the lock
     * @param id the account id, for the message
     */
    private static void lockOrThrow(ReentrantLock l, String id) {
        try {
            l.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LedgerException(LedgerException.Reason.INTERRUPTED, "interrupted while waiting for " + id);
        }
    }
}
