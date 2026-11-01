package com.lld.library;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The in-memory library.
 *
 * <p>Concurrency model. Each copy owns one {@code AtomicReference<CopyState>}, and every change to a copy is one compare-and-set on it, so two members can never both take the same copy. The state holds status, branch, member and deadline together, so a transfer between branches is also one compare-and-set and needs no lock (two opposite transfers cannot deadlock).
 *
 * <p>A checkout changes three things: the member's slot (title set and loan count), the copy, and the loan record. It reserves the member's slot first, then claims the copy, then writes the loan, and undoes the earlier steps if a later one fails. A failure therefore never leaves a loaned copy without a loan or the reverse.
 *
 * <p>The hold queue of a title is guarded by one lock per title. Only the decision "this free copy goes to the queue head or onto the shelf" and the hold bookkeeping take that lock. The lock-free checkout path stays fast, and the rule "a non-empty queue means no copy on a shelf" holds because every copy that becomes free goes through that decision.
 */
public final class InMemoryLibrary implements Library {

    /** One physical copy: a fixed id and title, and a state that changes only by compare-and-set. */
    private static final class Copy {
        final String id;
        final String isbn;
        final AtomicReference<CopyState> state;

        /** Creates a copy in the given first state. */
        Copy(String id, String isbn, CopyState first) {
            this.id = id;
            this.isbn = isbn;
            this.state = new AtomicReference<>(first);
        }
    }

    /** What the library knows about a member: the titles on loan (to forbid doubles) and the count (for the limit). */
    private static final class Member {
        final Set<String> borrowed = ConcurrentHashMap.newKeySet();
        final AtomicInteger loans = new AtomicInteger();
    }

    /** The hold queue of one title with the lock that guards it. */
    private static final class Queue {
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<String> waiting = new ArrayDeque<>();
    }

    private final Clock clock;
    private final LibraryPolicy policy;
    private final HoldNotifier notifier;
    private final Hook hook;
    private final Set<String> branches;
    private final Map<String, Title> titles = new ConcurrentHashMap<>();
    private final Map<String, List<Copy>> copiesByTitle = new ConcurrentHashMap<>();
    private final Map<String, Copy> copies = new ConcurrentHashMap<>();
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Map<String, Queue> queues = new ConcurrentHashMap<>();
    private final Map<String, Loan> issued = new ConcurrentHashMap<>();
    private final Map<String, Loan> open = new ConcurrentHashMap<>();
    private final AtomicLong copySeq = new AtomicLong();
    private final AtomicLong loanSeq = new AtomicLong();
    private final AtomicInteger notifierFailures = new AtomicInteger();

    /** Creates a library with no test hook. */
    public InMemoryLibrary(Set<String> branches, LibraryPolicy policy, Clock clock, HoldNotifier notifier) {
        this(branches, policy, clock, notifier, Hook.NONE);
    }

    /** Creates a library. The clock gives every timestamp, the notifier is told about ready holds, and the hook lets tests force interleavings. */
    public InMemoryLibrary(Set<String> branches, LibraryPolicy policy, Clock clock, HoldNotifier notifier, Hook hook) {
        this.branches = Set.copyOf(branches);
        this.policy = policy;
        this.clock = clock;
        this.notifier = notifier;
        this.hook = hook;
    }

    /** How many notifier calls threw so far. They are swallowed so a return never fails because of them. */
    public int notifierFailures() {
        return notifierFailures.get();
    }

    /** {@inheritDoc} */
    @Override
    public void addTitle(Title title) {
        titles.putIfAbsent(title.isbn(), title);
        copiesByTitle.computeIfAbsent(title.isbn(), k -> new CopyOnWriteArrayList<>());
        queues.computeIfAbsent(title.isbn(), k -> new Queue());
    }

    /** {@inheritDoc} */
    @Override
    public String addCopy(String isbn, String branch) {
        requireTitle(isbn);
        requireBranch(branch);
        Copy copy = new Copy("C" + copySeq.incrementAndGet(), isbn, CopyState.inTransit(branch));
        List<HoldNotice> notices = new ArrayList<>();
        Queue q = queues.get(isbn);
        q.lock.lock();
        try {
            copies.put(copy.id, copy);
            copiesByTitle.get(isbn).add(copy);
            // The copy starts "in transit" so nobody can grab it before it is routed to the queue head or the shelf.
            release(copy, copy.state.get(), branch, notices);
        } finally {
            q.lock.unlock();
        }
        send(notices);
        return copy.id;
    }

    /** {@inheritDoc} */
    @Override
    public void registerMember(String memberId) {
        members.putIfAbsent(memberId, new Member());
    }

    /** {@inheritDoc} */
    @Override
    public List<Title> search(String text) {
        String needle = text.toLowerCase(Locale.ROOT);
        return titles.values().stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(needle) || t.author().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Loan checkout(String memberId, String isbn, String branch) {
        Member member = members.get(memberId);
        if (member == null) {
            throw new LibraryException.UnknownMember(memberId);
        }
        requireTitle(isbn);
        requireBranch(branch);
        expireHolds(isbn);
        // Step 1: reserve the member's slot. One title once, and never above the limit.
        if (!member.borrowed.add(isbn)) {
            throw new LibraryException.AlreadyBorrowed(memberId, isbn);
        }
        if (!takeLoanSlot(member, memberId)) {
            member.borrowed.remove(isbn);
            throw new LibraryException.LoanLimitReached(memberId, policy.maxLoans());
        }
        Copy claimed = null;
        CopyState before = null;
        CopyState mine = null;
        boolean success = false;
        try {
            // Step 2: claim a copy with one compare-and-set. The copy held for this member is tried first, so a shelf copy is never taken while theirs stays blocked.
            for (int pass = 0; pass < 2 && claimed == null; pass++) {
                for (Copy c : copiesByTitle.get(isbn)) {
                    CopyState s = c.state.get();
                    if (!canTake(s, memberId, branch) || (s.status() == CopyStatus.ON_HOLD) != (pass == 0)) {
                        continue;
                    }
                    hook.at(Hook.BEFORE_CLAIM, c.id);
                    CopyState next = CopyState.loaned(branch, memberId);
                    if (c.state.compareAndSet(s, next)) {
                        claimed = c;
                        before = s;
                        mine = next;
                        break;
                    }
                }
            }
            if (claimed == null) {
                throw new LibraryException.NoCopyAvailable(isbn, branch);
            }
            hook.at(Hook.AFTER_CLAIM, claimed.id);
            // Step 3: write the loan record.
            Instant now = clock.instant();
            Loan loan = new Loan("L" + loanSeq.incrementAndGet(), claimed.id, memberId, isbn, now, dueDate(now));
            issued.put(loan.id(), loan);
            open.put(loan.id(), loan);
            success = true;
            return loan;
        } finally {
            if (!success) {
                undoCheckout(member, isbn, claimed, before, mine);
            }
        }
    }

    /**
     * Undoes a failed checkout in reverse order: the copy first, then the loan count and the title set. Runs for any Throwable, including an Error.
     *
     * <p>A copy taken from the shelf is handed to {@code release} under the title lock, so if someone joined the queue during the failed call the copy goes to them and not to the shelf. A copy taken from a hold is put back as it was. Notices go out after the lock is released.
     */
    private void undoCheckout(Member member, String isbn, Copy claimed, CopyState before, CopyState mine) {
        List<HoldNotice> notices = new ArrayList<>();
        if (claimed != null) {
            if (before.status() == CopyStatus.AVAILABLE) {
                Queue q = queues.get(isbn);
                q.lock.lock();
                try {
                    // compareAndSet compares by reference, so release is given the exact instance we installed.
                    release(claimed, mine, before.branch(), notices);
                } finally {
                    q.lock.unlock();
                }
            } else {
                claimed.state.compareAndSet(mine, before);
            }
        }
        member.loans.decrementAndGet();
        member.borrowed.remove(isbn);
        send(notices);
    }

    /** {@inheritDoc} */
    @Override
    public ReturnReceipt returnCopy(String loanId, String branch) {
        requireBranch(branch);
        Loan loan = open.remove(loanId);
        if (loan == null) {
            if (issued.containsKey(loanId)) {
                throw new LibraryException.AlreadyReturned(loanId);
            }
            throw new LibraryException.UnknownLoan(loanId);
        }
        // Only one caller gets past remove(), so everything below runs once per loan.
        Instant now = clock.instant();
        long days = daysLate(loan.dueAt(), now);
        ReturnReceipt receipt = new ReturnReceipt(loanId, now, days, fine(days));
        Copy copy = copies.get(loan.copyId());
        List<HoldNotice> notices = new ArrayList<>();
        Queue q = queues.get(loan.isbn());
        q.lock.lock();
        try {
            CopyState loaned = copy.state.get();
            if (!release(copy, loaned, branch, notices)) {
                throw new IllegalStateException("loaned copy " + copy.id + " changed under its borrower: " + loaned);
            }
        } finally {
            q.lock.unlock();
        }
        Member member = members.get(loan.memberId());
        member.borrowed.remove(loan.isbn());
        member.loans.decrementAndGet();
        send(notices);
        return receipt;
    }

    /** {@inheritDoc} */
    @Override
    public int placeHold(String memberId, String isbn) {
        Member member = members.get(memberId);
        if (member == null) {
            throw new LibraryException.UnknownMember(memberId);
        }
        requireTitle(isbn);
        List<HoldNotice> notices = new ArrayList<>();
        Queue q = queues.get(isbn);
        q.lock.lock();
        try {
            sweep(isbn, notices);
            if (member.borrowed.contains(isbn)) {
                throw new LibraryException.AlreadyBorrowed(memberId, isbn);
            }
            boolean waitingOrHeld = q.waiting.contains(memberId)
                    || copiesByTitle.get(isbn).stream().map(c -> c.state.get()).anyMatch(st -> st.status() == CopyStatus.ON_HOLD && memberId.equals(st.member()));
            if (waitingOrHeld) {
                throw new LibraryException.DuplicateHold(memberId, isbn);
            }
            if (copiesByTitle.get(isbn).stream().anyMatch(c -> c.state.get().status() == CopyStatus.AVAILABLE)) {
                throw new LibraryException.CopyAvailable(isbn);
            }
            q.waiting.addLast(memberId);
            return q.waiting.size();
        } finally {
            q.lock.unlock();
            send(notices);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void transfer(String copyId, String toBranch) {
        Copy copy = requireCopy(copyId);
        requireBranch(toBranch);
        while (true) {
            CopyState s = copy.state.get();
            if (s.status() != CopyStatus.AVAILABLE) {
                throw new LibraryException.CopyNotTransferable(copyId, "it is " + s.status());
            }
            if (s.branch().equals(toBranch)) {
                throw new LibraryException.CopyNotTransferable(copyId, "already at " + toBranch);
            }
            hook.at(Hook.BEFORE_TRANSFER, copyId);
            // One compare-and-set changes status and branch together: no lock, so no lock ordering to get wrong.
            if (copy.state.compareAndSet(s, CopyState.inTransit(toBranch))) {
                hook.at(Hook.IN_TRANSIT, copyId);
                return;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void receive(String copyId) {
        Copy copy = requireCopy(copyId);
        CopyState s = copy.state.get();
        if (s.status() != CopyStatus.IN_TRANSIT) {
            throw new LibraryException.NotInTransit(copyId);
        }
        List<HoldNotice> notices = new ArrayList<>();
        Queue q = queues.get(copy.isbn);
        boolean done;
        q.lock.lock();
        try {
            done = release(copy, s, s.branch(), notices);
        } finally {
            q.lock.unlock();
        }
        if (!done) {
            throw new LibraryException.NotInTransit(copyId);
        }
        send(notices);
    }

    /** {@inheritDoc} */
    @Override
    public void sweepHolds() {
        for (String isbn : titles.keySet()) {
            expireHolds(isbn);
        }
    }

    /** {@inheritDoc} */
    @Override
    public CopyState stateOf(String copyId) {
        return requireCopy(copyId).state.get();
    }

    /** {@inheritDoc} */
    @Override
    public List<String> holdQueue(String isbn) {
        requireTitle(isbn);
        Queue q = queues.get(isbn);
        q.lock.lock();
        try {
            return List.copyOf(q.waiting);
        } finally {
            q.lock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public int openLoans(String memberId) {
        Member m = members.get(memberId);
        if (m == null) {
            throw new LibraryException.UnknownMember(memberId);
        }
        return m.loans.get();
    }

    /** {@inheritDoc} */
    @Override
    public int availableCopies(String isbn, String branch) {
        requireTitle(isbn);
        return (int) copiesByTitle.get(isbn).stream().map(c -> c.state.get()).filter(st -> st.status() == CopyStatus.AVAILABLE && st.branch().equals(branch)).count();
    }

    /** Whether this member may take a copy in this state at this branch: a shelf copy, or a hold kept for them whose window is still open. */
    private boolean canTake(CopyState s, String memberId, String branch) {
        if (!s.branch().equals(branch)) {
            return false;
        }
        if (s.status() == CopyStatus.AVAILABLE) {
            return true;
        }
        // Half-open window: the member may collect strictly before the deadline.
        return s.status() == CopyStatus.ON_HOLD && memberId.equals(s.member()) && clock.instant().isBefore(s.until());
    }

    /** Takes one of the member's loan slots with a compare-and-set loop. Returns false when the member is at the limit, without ever going above it. */
    private boolean takeLoanSlot(Member m, String memberId) {
        while (true) {
            int n = m.loans.get();
            if (n >= policy.maxLoans()) {
                return false;
            }
            hook.at(Hook.BEFORE_SLOT, memberId);
            if (m.loans.compareAndSet(n, n + 1)) {
                return true;
            }
        }
    }

    /** The due instant: the loan day count added as calendar days in the library's zone, so a loan keeps its wall-clock time across daylight saving changes. */
    private Instant dueDate(Instant borrowedAt) {
        return borrowedAt.atZone(policy.zone()).plusDays(policy.loanDays()).toInstant();
    }

    /** Days late as local 24-hour periods that start at the due time of day in the library's zone, rounded up. Returning at or before the due instant is 0 days (half-open: the due instant itself is on time). */
    private long daysLate(Instant due, Instant returned) {
        if (!returned.isAfter(due)) {
            return 0;
        }
        ZonedDateTime d = due.atZone(policy.zone());
        ZonedDateTime r = returned.atZone(policy.zone());
        long whole = ChronoUnit.DAYS.between(d, r);
        return d.plusDays(whole).isBefore(r) ? whole + 1 : whole;
    }

    /** The fine for the given late days: days times the daily rate, never above the cap, and without overflow. */
    private long fine(long days) {
        long perDay = policy.finePerDayMinor();
        if (perDay == 0 || days == 0) {
            return 0;
        }
        return days > policy.fineCapMinor() / perDay ? policy.fineCapMinor() : days * perDay;
    }

    /**
     * Decides where a free copy goes: to the head of the title's queue (as ON_HOLD with a pickup deadline) or onto the shelf. Caller holds the title lock.
     *
     * <p>Returns false if the copy was no longer in the expected state (someone else changed it first), in which case nothing happens and the queue is untouched.
     */
    private boolean release(Copy copy, CopyState from, String branch, List<HoldNotice> notices) {
        Queue q = queues.get(copy.isbn);
        String head = q.waiting.peekFirst();
        if (head == null) {
            return copy.state.compareAndSet(from, CopyState.available(branch));
        }
        Instant until = clock.instant().plus(policy.holdPickup());
        if (!copy.state.compareAndSet(from, CopyState.onHold(branch, head, until))) {
            return false;
        }
        q.waiting.pollFirst();
        notices.add(new HoldNotice(head, copy.isbn, copy.id, branch, until));
        return true;
    }

    /** Passes every expired hold of the title on to the next member (or the shelf), taking the title lock only if something has expired. */
    private void expireHolds(String isbn) {
        Instant now = clock.instant();
        boolean any = copiesByTitle.get(isbn).stream().anyMatch(c -> isExpired(c.state.get(), now));
        if (!any) {
            return;
        }
        List<HoldNotice> notices = new ArrayList<>();
        Queue q = queues.get(isbn);
        q.lock.lock();
        try {
            sweep(isbn, notices);
        } finally {
            q.lock.unlock();
        }
        send(notices);
    }

    /** Whether a state is a hold whose pickup window has ended (it ends at exactly the deadline). */
    private static boolean isExpired(CopyState s, Instant now) {
        return s.status() == CopyStatus.ON_HOLD && !now.isBefore(s.until());
    }

    /** Re-routes every expired hold of the title. Caller holds the title lock. If a member collects at the same moment, the compare-and-set decides and the loser backs off. */
    private void sweep(String isbn, List<HoldNotice> notices) {
        Instant now = clock.instant();
        for (Copy c : copiesByTitle.get(isbn)) {
            CopyState s = c.state.get();
            if (isExpired(s, now)) {
                release(c, s, s.branch(), notices);
            }
        }
    }

    /** Sends notices outside any lock. A throwing notifier is counted and ignored: the return or hold that caused it has already happened. */
    private void send(List<HoldNotice> notices) {
        for (HoldNotice n : notices) {
            try {
                notifier.holdReady(n);
            } catch (RuntimeException e) {
                notifierFailures.incrementAndGet();
            }
        }
    }

    /** Looks up a title or throws UnknownTitle. */
    private void requireTitle(String isbn) {
        if (!titles.containsKey(isbn)) {
            throw new LibraryException.UnknownTitle(isbn);
        }
    }

    /** Checks the branch exists or throws UnknownBranch. */
    private void requireBranch(String branch) {
        if (!branches.contains(branch)) {
            throw new LibraryException.UnknownBranch(branch);
        }
    }

    /** Looks up a copy or throws UnknownCopy. */
    private Copy requireCopy(String id) {
        Copy c = copies.get(id);
        if (c == null) {
            throw new LibraryException.UnknownCopy(id);
        }
        return c;
    }
}
