package com.lld.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Single-thread behaviour: loans, fines, limits, holds, notifications and transfers. */
class LibraryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final LibraryPolicy POLICY = new LibraryPolicy(2, 14, 25, 100, Duration.ofDays(2), ZoneId.of("UTC"));

    private MutableClock clock;
    private List<HoldNotice> notices;
    private InMemoryLibrary lib;

    /** Builds a fresh library with two branches, three titles (DUNE, EMMA, ULY) and members ann, bob and cat. */
    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        notices = new ArrayList<>();
        lib = new InMemoryLibrary(Set.of("north", "south"), POLICY, clock, notices::add);
        lib.addTitle(new Title("DUNE", "Dune", "Herbert"));
        lib.addTitle(new Title("EMMA", "Emma", "Austen"));
        lib.addTitle(new Title("ULY", "Ulysses", "Joyce"));
        for (String m : List.of("ann", "bob", "cat")) {
            lib.registerMember(m);
        }
    }

    /** Proves search matches name or author, ignoring case. */
    @Test
    void searchFindsByNameOrAuthor() {
        assertEquals(1, lib.search("DUN").size());
        assertEquals("Emma", lib.search("austen").get(0).name());
    }

    /** Proves a checkout marks the copy loaned, counts the loan, and sets the due date loanDays later. */
    @Test
    void checkoutLoansTheCopy() {
        String c = lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        assertEquals(c, l.copyId());
        assertEquals(CopyStatus.LOANED, lib.stateOf(c).status());
        assertEquals(1, lib.openLoans("ann"));
        assertEquals(T0.plus(Duration.ofDays(14)), l.dueAt());
    }

    /** Proves the same member cannot borrow the same title twice, and a second member finds no copy. */
    @Test
    void noDoubleBorrowAndNoCopyLeft() {
        lib.addCopy("DUNE", "north");
        lib.checkout("ann", "DUNE", "north");
        assertThrows(LibraryException.AlreadyBorrowed.class, () -> lib.checkout("ann", "DUNE", "north"));
        assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("bob", "DUNE", "north"));
        assertEquals(0, lib.openLoans("bob"));
    }

    /** Proves the loan limit stops the third loan and leaves the count at the limit. */
    @Test
    void loanLimitIsEnforced() {
        for (String isbn : List.of("DUNE", "EMMA", "ULY")) {
            lib.addCopy(isbn, "north");
        }
        lib.checkout("ann", "DUNE", "north");
        lib.checkout("ann", "EMMA", "north");
        assertThrows(LibraryException.LoanLimitReached.class, () -> lib.checkout("ann", "ULY", "north"));
        assertEquals(2, lib.openLoans("ann"));
        assertEquals(1, lib.availableCopies("ULY", "north"));
    }

    /** Proves unknown ids fail with their own error types. */
    @Test
    void unknownIdsFailClearly() {
        assertThrows(LibraryException.UnknownMember.class, () -> lib.checkout("zed", "DUNE", "north"));
        assertThrows(LibraryException.UnknownTitle.class, () -> lib.checkout("ann", "NOPE", "north"));
        assertThrows(LibraryException.UnknownBranch.class, () -> lib.checkout("ann", "DUNE", "mars"));
        assertThrows(LibraryException.UnknownLoan.class, () -> lib.returnCopy("L99", "north"));
    }

    /** Proves a return exactly at the due instant is on time (half-open) and costs nothing. */
    @Test
    void returnAtExactlyDueIsNotLate() {
        lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(14));
        ReturnReceipt r = lib.returnCopy(l.id(), "north");
        assertEquals(0, r.daysLate());
        assertEquals(0, r.fineMinor());
    }

    /** Proves one nanosecond after due is one whole day late, and that a full day is still one day while a day and a nanosecond is two. */
    @Test
    void daysLateRoundUp() {
        lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(14).plusNanos(1));
        ReturnReceipt r = lib.returnCopy(l.id(), "north");
        assertEquals(1, r.daysLate());
        assertEquals(25, r.fineMinor());
        Loan l2 = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(15));
        assertEquals(1, lib.returnCopy(l2.id(), "north").daysLate());
        Loan l3 = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(15).plusNanos(1));
        assertEquals(2, lib.returnCopy(l3.id(), "north").daysLate());
    }

    /** Proves the cap boundary: 4 days is exactly the cap (100), 5 days would be 125 but stays 100. */
    @Test
    void fineCapBoundary() {
        lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(14 + 3).plusNanos(1));
        assertEquals(100, lib.returnCopy(l.id(), "north").fineMinor());
        Loan l2 = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(14 + 2).plusNanos(1));
        assertEquals(75, lib.returnCopy(l2.id(), "north").fineMinor());
        Loan l3 = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(14 + 4).plusNanos(1));
        assertEquals(100, lib.returnCopy(l3.id(), "north").fineMinor());
        Loan l4 = lib.checkout("ann", "DUNE", "north");
        clock.advance(Duration.ofDays(14 + 100000));
        assertEquals(100, lib.returnCopy(l4.id(), "north").fineMinor());
    }

    /** Proves days are counted in the library's zone across a daylight saving change: a loan lent at 10:00 New York time stays due at 10:00 local, so a return at 10:00 local on the due day is on time. */
    @Test
    void dueDateKeepsLocalTimeAcrossDst() {
        ZoneId ny = ZoneId.of("America/New_York");
        LibraryPolicy p = new LibraryPolicy(2, 14, 25, 1000, Duration.ofDays(2), ny);
        Instant start = Instant.parse("2026-03-01T15:00:00Z");
        MutableClock c = new MutableClock(start);
        InMemoryLibrary l = new InMemoryLibrary(Set.of("north"), p, c, HoldNotifier.NONE);
        l.addTitle(new Title("DUNE", "Dune", "H"));
        l.registerMember("ann");
        l.addCopy("DUNE", "north");
        Loan loan = l.checkout("ann", "DUNE", "north");
        // Clocks go forward on 8 March, so 14 local days is only 14 days minus 1 hour of real time.
        assertEquals(start.plus(Duration.ofDays(14)).minus(Duration.ofHours(1)), loan.dueAt());
        c.advance(Duration.ofDays(14).minusHours(1));
        assertEquals(0, l.returnCopy(loan.id(), "north").daysLate());
    }

    /** Proves returning the same loan twice fails with AlreadyReturned and does not change the member count or copy. */
    @Test
    void returningTwiceFails() {
        String c = lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        lib.returnCopy(l.id(), "north");
        assertThrows(LibraryException.AlreadyReturned.class, () -> lib.returnCopy(l.id(), "north"));
        assertEquals(0, lib.openLoans("ann"));
        assertEquals(CopyStatus.AVAILABLE, lib.stateOf(c).status());
    }

    /** Proves a return puts the copy at the branch where it was handed in, even if it was lent from another. */
    @Test
    void returnToAnotherBranch() {
        String c = lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        lib.returnCopy(l.id(), "south");
        assertEquals("south", lib.stateOf(c).branch());
    }

    /** Proves holds are FIFO: the first in line gets the returned copy, the notice names them, and the queue shrinks. */
    @Test
    void returnedCopyGoesToHeadOfQueue() {
        String c = lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        assertEquals(1, lib.placeHold("bob", "DUNE"));
        assertEquals(2, lib.placeHold("cat", "DUNE"));
        lib.returnCopy(l.id(), "north");
        CopyState s = lib.stateOf(c);
        assertEquals(CopyStatus.ON_HOLD, s.status());
        assertEquals("bob", s.member());
        assertEquals(List.of("cat"), lib.holdQueue("DUNE"));
        assertEquals(1, notices.size());
        assertEquals("bob", notices.get(0).memberId());
        assertEquals(T0.plus(Duration.ofDays(2)), notices.get(0).expiresAt());
    }

    /** Proves a held copy is only for its member: others cannot take it, the member can. */
    @Test
    void heldCopyIsOnlyForTheHolder() {
        lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        lib.placeHold("bob", "DUNE");
        lib.returnCopy(l.id(), "north");
        assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("cat", "DUNE", "north"));
        assertEquals("bob", lib.checkout("bob", "DUNE", "north").memberId());
    }

    /** Proves the pickup window is half-open: one nanosecond before the deadline the holder can collect, at the deadline the copy passes to the next in line. */
    @Test
    void holdExpiresAtDeadlineAndPassesOn() {
        String c = lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        lib.placeHold("bob", "DUNE");
        lib.placeHold("cat", "DUNE");
        lib.returnCopy(l.id(), "north");
        clock.advance(Duration.ofDays(2).minusNanos(1));
        lib.sweepHolds();
        assertEquals("bob", lib.stateOf(c).member());
        clock.advance(Duration.ofNanos(1));
        assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("bob", "DUNE", "north"));
        CopyState s = lib.stateOf(c);
        assertEquals("cat", s.member());
        assertEquals(T0.plus(Duration.ofDays(4)), s.until());
        assertEquals(2, notices.size());
        assertEquals("cat", notices.get(1).memberId());
    }

    /** Proves an expired hold with nobody else waiting puts the copy back on the shelf. */
    @Test
    void expiredHoldWithEmptyQueueReturnsToShelf() {
        String c = lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        lib.placeHold("bob", "DUNE");
        lib.returnCopy(l.id(), "north");
        clock.advance(Duration.ofDays(2));
        lib.sweepHolds();
        assertEquals(CopyStatus.AVAILABLE, lib.stateOf(c).status());
        assertEquals("cat", lib.checkout("cat", "DUNE", "north").memberId());
    }

    /** Proves a member cannot hold a title they have on loan, cannot hold twice, and cannot hold when a copy is on the shelf. */
    @Test
    void holdRules() {
        lib.addCopy("DUNE", "north");
        Loan l = lib.checkout("ann", "DUNE", "north");
        assertThrows(LibraryException.AlreadyBorrowed.class, () -> lib.placeHold("ann", "DUNE"));
        lib.placeHold("bob", "DUNE");
        assertThrows(LibraryException.DuplicateHold.class, () -> lib.placeHold("bob", "DUNE"));
        lib.returnCopy(l.id(), "north");
        assertThrows(LibraryException.DuplicateHold.class, () -> lib.placeHold("bob", "DUNE"));
        lib.checkout("bob", "DUNE", "north");
        lib.addCopy("DUNE", "north");
        assertThrows(LibraryException.CopyAvailable.class, () -> lib.placeHold("cat", "DUNE"));
    }

    /** Proves a new copy added while people wait goes straight to the head of the queue. */
    @Test
    void newCopyServesTheQueue() {
        lib.addCopy("DUNE", "north");
        lib.checkout("ann", "DUNE", "north");
        lib.placeHold("bob", "DUNE");
        String c2 = lib.addCopy("DUNE", "south");
        assertEquals("bob", lib.stateOf(c2).member());
        assertEquals(1, notices.size());
    }

    /** Proves a holder who also sees a free shelf copy gets the copy held for them, not the shelf one, so the held copy is not left blocked until its window ends. */
    @Test
    void holderTakesTheirHeldCopyNotTheShelfCopy() {
        String c1 = lib.addCopy("DUNE", "north");
        String c2 = lib.addCopy("DUNE", "north");
        lib.registerMember("dan");
        Loan annLoan = lib.checkout("ann", "DUNE", "north");
        Loan danLoan = lib.checkout("dan", "DUNE", "north");
        assertEquals(c1, annLoan.copyId());
        assertEquals(c2, danLoan.copyId());
        lib.placeHold("bob", "DUNE");
        lib.returnCopy(danLoan.id(), "north");
        lib.returnCopy(annLoan.id(), "north");
        assertEquals(CopyStatus.ON_HOLD, lib.stateOf(c2).status());
        assertEquals(CopyStatus.AVAILABLE, lib.stateOf(c1).status());
        Loan bobLoan = lib.checkout("bob", "DUNE", "north");
        assertEquals(c2, bobLoan.copyId());
        assertEquals(CopyStatus.AVAILABLE, lib.stateOf(c1).status());
        // The shelf copy is free for anyone else right away.
        assertEquals(c1, lib.checkout("cat", "DUNE", "north").copyId());
    }

    /** Proves a day count across the autumn clock change in New York: due 10:00 EDT on 31 Oct, returned 09:30 EST on 1 Nov is 24.5 real hours but less than one local day, so it is 1 day late (a ceil of hours over 24 would say 2). */
    @Test
    void daysLateCountsLocalDaysAcrossDstEnd() {
        ZoneId ny = ZoneId.of("America/New_York");
        LibraryPolicy p = new LibraryPolicy(2, 14, 25, 1000, Duration.ofDays(2), ny);
        MutableClock c = new MutableClock(Instant.parse("2026-10-17T14:00:00Z"));
        InMemoryLibrary l = new InMemoryLibrary(Set.of("north"), p, c, HoldNotifier.NONE);
        l.addTitle(new Title("DUNE", "Dune", "H"));
        l.registerMember("ann");
        l.addCopy("DUNE", "north");
        Loan loan = l.checkout("ann", "DUNE", "north");
        assertEquals(Instant.parse("2026-10-31T14:00:00Z"), loan.dueAt());
        c.advance(Duration.between(c.instant(), Instant.parse("2026-11-01T14:30:00Z")));
        ReturnReceipt r = l.returnCopy(loan.id(), "north");
        assertEquals(1, r.daysLate());
        assertEquals(25, r.fineMinor());
    }

    /** Proves a notifier that throws does not break the return: the receipt comes back, the hold is still made and the failure is counted. */
    @Test
    void throwingNotifierDoesNotBreakReturn() {
        InMemoryLibrary l = new InMemoryLibrary(Set.of("north"), POLICY, clock, n -> {
            throw new IllegalStateException("mail server down");
        });
        l.addTitle(new Title("DUNE", "Dune", "H"));
        l.registerMember("ann");
        l.registerMember("bob");
        String c = l.addCopy("DUNE", "north");
        Loan loan = l.checkout("ann", "DUNE", "north");
        l.placeHold("bob", "DUNE");
        ReturnReceipt r = l.returnCopy(loan.id(), "north");
        assertEquals(0, r.fineMinor());
        assertEquals("bob", l.stateOf(c).member());
        assertEquals(0, l.openLoans("ann"));
        assertEquals(1, l.notifierFailures());
    }

    /** Proves a transfer puts the copy in transit, blocks checkout, and receive makes it borrowable at the destination only. */
    @Test
    void transferBlocksCheckoutUntilReceived() {
        String c = lib.addCopy("DUNE", "north");
        lib.transfer(c, "south");
        assertEquals(CopyStatus.IN_TRANSIT, lib.stateOf(c).status());
        assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("ann", "DUNE", "north"));
        assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("ann", "DUNE", "south"));
        lib.receive(c);
        assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("ann", "DUNE", "north"));
        assertEquals("ann", lib.checkout("ann", "DUNE", "south").memberId());
    }

    /** Proves transfer and receive refuse bad states: loaned copy, same branch, not in transit. */
    @Test
    void transferRules() {
        String c = lib.addCopy("DUNE", "north");
        assertThrows(LibraryException.CopyNotTransferable.class, () -> lib.transfer(c, "north"));
        assertThrows(LibraryException.NotInTransit.class, () -> lib.receive(c));
        lib.checkout("ann", "DUNE", "north");
        assertThrows(LibraryException.CopyNotTransferable.class, () -> lib.transfer(c, "south"));
        assertThrows(LibraryException.UnknownCopy.class, () -> lib.transfer("C99", "south"));
    }

    /** Proves a copy arriving after a transfer goes to a waiting member, not the shelf. */
    @Test
    void receivedCopyServesTheQueue() {
        String c = lib.addCopy("DUNE", "north");
        lib.transfer(c, "south");
        lib.placeHold("ann", "DUNE");
        lib.receive(c);
        assertEquals("ann", lib.stateOf(c).member());
        assertTrue(lib.holdQueue("DUNE").isEmpty());
    }

    /** Proves a policy with nonsense numbers is rejected at construction. */
    @Test
    void badPolicyRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LibraryPolicy(0, 14, 25, 100, Duration.ofDays(1), ZoneId.of("UTC")));
        assertThrows(IllegalArgumentException.class, () -> new LibraryPolicy(1, 14, -1, 100, Duration.ofDays(1), ZoneId.of("UTC")));
        assertThrows(IllegalArgumentException.class, () -> new LibraryPolicy(1, 14, 1, 100, Duration.ZERO, ZoneId.of("UTC")));
    }
}
