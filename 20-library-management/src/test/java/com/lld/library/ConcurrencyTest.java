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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Threads race for copies, loan slots and transfers. Interleavings are forced with barriers, never sleeps. */
class ConcurrencyTest {

    private static final LibraryPolicy POLICY = new LibraryPolicy(3, 14, 25, 1000, Duration.ofDays(2), ZoneId.of("UTC"));

    /** The result of one racing call: a loan, or the error it got. */
    private record Outcome(Loan loan, Throwable error) {
    }

    /** Tries a checkout and turns a LibraryException into an Outcome so racing threads never throw. */
    private static Outcome attempt(Library lib, String member, String isbn) {
        try {
            return new Outcome(lib.checkout(member, isbn, "north"), null);
        } catch (LibraryException e) {
            return new Outcome(null, e);
        }
    }

    /** Builds a library with the given hook and members m0..m(n-1), and titles T0..T(t-1) with the given copies each at north. */
    private static InMemoryLibrary build(Hook hook, int members, int titles, int copiesEach) {
        InMemoryLibrary lib = new InMemoryLibrary(Set.of("north", "south"), POLICY, new MutableClock(Instant.EPOCH), HoldNotifier.NONE, hook);
        for (int m = 0; m < members; m++) {
            lib.registerMember("m" + m);
        }
        for (int t = 0; t < titles; t++) {
            lib.addTitle(new Title("T" + t, "Title" + t, "A"));
            for (int c = 0; c < copiesEach; c++) {
                lib.addCopy("T" + t, "north");
            }
        }
        return lib;
    }

    /** A hook that makes the first two threads reaching BEFORE_CLAIM wait for each other, so both have looked before either takes. */
    private static Hook bothSeeFree() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        return (point, copyId) -> {
            if (Hook.BEFORE_CLAIM.equals(point)) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("members never met", e);
                }
            }
        };
    }

    /** Proves two members who both see the last copy as free (forced by a barrier) give it to exactly one, with one loan, one count and one NoCopyAvailable. */
    @Test
    void lastCopyRaceExactlyOneWins() throws Exception {
        InMemoryLibrary lib = build(bothSeeFree(), 2, 1, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> attempt(lib, "m0", "T0"));
            Future<Outcome> b = pool.submit(() -> attempt(lib, "m1", "T0"));
            List<Outcome> results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(o -> o.loan() != null).count());
            assertEquals(1, results.stream().filter(o -> o.error() instanceof LibraryException.NoCopyAvailable).count());
            assertEquals(1, lib.openLoans("m0") + lib.openLoans("m1"));
            assertEquals(CopyStatus.LOANED, lib.stateOf("C1").status());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves 64 members released together on 10 copies produce exactly 10 loans on 10 distinct copies and 54 NoCopyAvailable. */
    @Test
    void manyMembersGetExactlyTheCopies() throws Exception {
        InMemoryLibrary lib = build(Hook.NONE, 64, 1, 10);
        AtomicInteger n = new AtomicInteger();
        List<Outcome> results = runTogether(64, () -> attempt(lib, "m" + n.getAndIncrement(), "T0"));
        List<Outcome> won = results.stream().filter(o -> o.loan() != null).toList();
        assertEquals(10, won.size());
        assertEquals(10, won.stream().map(o -> o.loan().copyId()).distinct().count());
        assertEquals(54, results.stream().filter(o -> o.error() instanceof LibraryException.NoCopyAvailable).count());
        assertEquals(0, lib.availableCopies("T0", "north"));
    }

    /** Proves a member hammering with 16 threads over 8 titles never exceeds the limit of 3 and ends with exactly 3 loans and exactly 3 loaned copies. */
    @Test
    void loanLimitHoldsUnderConcurrency() throws Exception {
        InMemoryLibrary lib = build(Hook.NONE, 1, 8, 1);
        AtomicInteger n = new AtomicInteger();
        List<Outcome> results = runTogether(16, () -> attempt(lib, "m0", "T" + (n.getAndIncrement() % 8)));
        assertEquals(3, results.stream().filter(o -> o.loan() != null).count());
        assertEquals(3, lib.openLoans("m0"));
        long loaned = 0;
        for (int c = 1; c <= 8; c++) {
            if (lib.stateOf("C" + c).status() == CopyStatus.LOANED) {
                loaned++;
            }
        }
        assertEquals(3, loaned);
        assertTrue(results.stream().filter(o -> o.loan() == null).allMatch(o -> o.error() instanceof LibraryException.LoanLimitReached || o.error() instanceof LibraryException.AlreadyBorrowed || o.error() instanceof LibraryException.NoCopyAvailable));
    }

    /** Proves the same member racing for the same title with 8 threads gets it once, because of the one-title-once rule. */
    @Test
    void sameMemberSameTitleOnce() throws Exception {
        InMemoryLibrary lib = build(Hook.NONE, 1, 1, 5);
        List<Outcome> results = runTogether(8, () -> attempt(lib, "m0", "T0"));
        assertEquals(1, results.stream().filter(o -> o.loan() != null).count());
        assertEquals(1, lib.openLoans("m0"));
        assertEquals(4, lib.availableCopies("T0", "north"));
    }

    /** Proves a failure after the copy is claimed (the hook throws before the loan is written) is fully undone: copy back on the shelf, count back to zero, and the member can borrow again. */
    @Test
    void failureAfterClaimLeavesNothingBehind() {
        AtomicInteger calls = new AtomicInteger();
        Hook failOnce = (point, id) -> {
            if (Hook.AFTER_CLAIM.equals(point) && calls.getAndIncrement() == 0) {
                throw new IllegalStateException("disk full");
            }
        };
        InMemoryLibrary lib = build(failOnce, 1, 1, 1);
        assertThrows(IllegalStateException.class, () -> lib.checkout("m0", "T0", "north"));
        assertEquals(CopyStatus.AVAILABLE, lib.stateOf("C1").status());
        assertEquals(0, lib.openLoans("m0"));
        assertEquals(1, lib.availableCopies("T0", "north"));
        assertEquals("C1", lib.checkout("m0", "T0", "north").copyId());
    }

    /** Proves two returns of the same loan racing give one receipt and one AlreadyReturned, with the count dropping by exactly one. */
    @Test
    void doubleReturnRaceReturnsOnce() throws Exception {
        InMemoryLibrary lib = build(Hook.NONE, 1, 1, 1);
        Loan loan = lib.checkout("m0", "T0", "north");
        List<Boolean> results = runTogether(8, () -> {
            try {
                lib.returnCopy(loan.id(), "north");
                return true;
            } catch (LibraryException.AlreadyReturned e) {
                return false;
            }
        });
        assertEquals(1, results.stream().filter(b -> b).count());
        assertEquals(0, lib.openLoans("m0"));
        assertEquals(1, lib.availableCopies("T0", "north"));
    }

    /** Proves two transfers of the same shelf copy to two different branches, both forced to look before either moves it, give exactly one winner: the other is refused and the copy ends in transit to the winner's branch. */
    @Test
    void racingTransfersOfOneCopyExactlyOneWins() throws Exception {
        CyclicBarrier bothLooked = new CyclicBarrier(2);
        Hook meet = (point, id) -> {
            if (Hook.BEFORE_TRANSFER.equals(point)) {
                try {
                    bothLooked.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("transfers never met", e);
                }
            }
        };
        InMemoryLibrary lib = new InMemoryLibrary(Set.of("north", "south", "east"), POLICY, new MutableClock(Instant.EPOCH), HoldNotifier.NONE, meet);
        lib.addTitle(new Title("T0", "T", "A"));
        lib.addCopy("T0", "north");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> toSouth = pool.submit(() -> moved(lib, "south"));
            Future<Boolean> toEast = pool.submit(() -> moved(lib, "east"));
            boolean south = toSouth.get(20, TimeUnit.SECONDS);
            boolean east = toEast.get(20, TimeUnit.SECONDS);
            assertEquals(1, (south ? 1 : 0) + (east ? 1 : 0));
            assertEquals(CopyStatus.IN_TRANSIT, lib.stateOf("C1").status());
            assertEquals(south ? "south" : "east", lib.stateOf("C1").branch());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Tries to move copy C1 to the branch and reports whether this transfer was accepted. */
    private static boolean moved(Library lib, String to) {
        try {
            lib.transfer("C1", to);
            return true;
        } catch (LibraryException.CopyNotTransferable e) {
            return false;
        }
    }

    /** Proves two copies of one title moved in opposite directions at the same moment (each paused in transit by the hook until the other is also in transit) both complete and both can be received. */
    @Test
    void crossTransfersOfTwoCopiesBothComplete() throws Exception {
        CyclicBarrier bothInTransit = new CyclicBarrier(2);
        Hook meet = (point, id) -> {
            if (Hook.IN_TRANSIT.equals(point)) {
                try {
                    bothInTransit.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        InMemoryLibrary lib = build(meet, 1, 1, 1);
        lib.addCopy("T0", "south");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> lib.transfer("C1", "south"));
            Future<?> b = pool.submit(() -> lib.transfer("C2", "north"));
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
            assertEquals(CopyStatus.IN_TRANSIT, lib.stateOf("C1").status());
            assertEquals(CopyStatus.IN_TRANSIT, lib.stateOf("C2").status());
            assertThrows(LibraryException.NoCopyAvailable.class, () -> lib.checkout("m0", "T0", "south"));
            lib.receive("C1");
            lib.receive("C2");
            assertEquals("south", lib.stateOf("C1").branch());
            assertEquals("north", lib.stateOf("C2").branch());
            assertEquals(1, lib.availableCopies("T0", "south"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves a checkout that looks at a copy just before a transfer moves it cannot take it: the compare-and-set fails because the state changed, and the member gets NoCopyAvailable. */
    @Test
    void checkoutLosingToTransferCannotTakeTheCopy() throws Exception {
        CountDownLatch lookedAtCopy = new CountDownLatch(1);
        CountDownLatch transferred = new CountDownLatch(1);
        Hook hook = (point, id) -> {
            if (Hook.BEFORE_CLAIM.equals(point)) {
                lookedAtCopy.countDown();
                try {
                    if (!transferred.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("transfer never finished");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        InMemoryLibrary lib = build(hook, 1, 1, 1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Outcome> a = pool.submit(() -> attempt(lib, "m0", "T0"));
            assertTrue(lookedAtCopy.await(10, TimeUnit.SECONDS));
            lib.transfer("C1", "south");
            transferred.countDown();
            Outcome o = a.get(20, TimeUnit.SECONDS);
            assertTrue(o.error() instanceof LibraryException.NoCopyAvailable);
            assertEquals(CopyStatus.IN_TRANSIT, lib.stateOf("C1").status());
            assertEquals(0, lib.openLoans("m0"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves a holder who passed the "is it mine and still open" check just before the window ends cannot take the copy once the sweep has passed it on: the claim fails, the copy stays with the next member and the holder has no loan. */
    @Test
    void holderLosingTheExpiryRaceCannotTakeTheCopy() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CountDownLatch bChecked = new CountDownLatch(1);
        CountDownLatch swept = new CountDownLatch(1);
        Hook pauseB = (point, id) -> {
            if (Hook.BEFORE_CLAIM.equals(point)) {
                bChecked.countDown();
                try {
                    if (!swept.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("sweep never finished");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        AtomicReference<Library> ref = new AtomicReference<>();
        Hook armed = (point, id) -> {
            if (ref.get() != null) {
                pauseB.at(point, id);
            }
        };
        InMemoryLibrary lib = new InMemoryLibrary(Set.of("north"), POLICY, clock, HoldNotifier.NONE, armed);
        lib.addTitle(new Title("T0", "T", "A"));
        for (String m : List.of("a", "b", "c")) {
            lib.registerMember(m);
        }
        lib.addCopy("T0", "north");
        Loan l = lib.checkout("a", "T0", "north");
        lib.placeHold("b", "T0");
        lib.placeHold("c", "T0");
        lib.returnCopy(l.id(), "north");
        Instant until = lib.stateOf("C1").until();
        clock.advance(Duration.between(clock.instant(), until).minusNanos(1));
        ref.set(lib);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Outcome> b = pool.submit(() -> attempt(lib, "b", "T0"));
            assertTrue(bChecked.await(10, TimeUnit.SECONDS));
            clock.advance(Duration.ofNanos(1));
            lib.sweepHolds();
            swept.countDown();
            Outcome ob = b.get(20, TimeUnit.SECONDS);
            assertTrue(ob.error() instanceof LibraryException.NoCopyAvailable);
            assertEquals(CopyStatus.ON_HOLD, lib.stateOf("C1").status());
            assertEquals("c", lib.stateOf("C1").member());
            assertEquals(0, lib.openLoans("b"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves two checkouts by one member who has limit-1 loans, both forced to see room before either takes the last slot, give exactly one loan and one LoanLimitReached, and the count ends at the limit. */
    @Test
    void lastLoanSlotRaceExactlyOneWins() throws Exception {
        CyclicBarrier bothSawRoom = new CyclicBarrier(2);
        AtomicBoolean armed = new AtomicBoolean();
        Hook meet = (point, id) -> {
            if (armed.get() && Hook.BEFORE_SLOT.equals(point)) {
                try {
                    bothSawRoom.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("checkouts never met", e);
                }
            }
        };
        InMemoryLibrary lib = build(meet, 1, 4, 1);
        lib.checkout("m0", "T0", "north");
        lib.checkout("m0", "T1", "north");
        armed.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> a = pool.submit(() -> attempt(lib, "m0", "T2"));
            Future<Outcome> b = pool.submit(() -> attempt(lib, "m0", "T3"));
            List<Outcome> results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(o -> o.loan() != null).count());
            assertEquals(1, results.stream().filter(o -> o.error() instanceof LibraryException.LoanLimitReached).count());
            assertEquals(3, lib.openLoans("m0"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** Proves that when the undo of a failed checkout finds people waiting for the title, the copy goes to the queue head (ON_HOLD, queue emptied) instead of the shelf, so the rule "a waiting queue means no shelf copy" survives the failure. */
    @Test
    void undoAfterFailureServesTheQueue() {
        AtomicReference<Library> ref = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Hook holdThenFail = (point, id) -> {
            if (Hook.AFTER_CLAIM.equals(point) && calls.getAndIncrement() == 0) {
                ref.get().placeHold("m1", "T0");
                throw new IllegalStateException("disk full");
            }
        };
        InMemoryLibrary lib = build(holdThenFail, 2, 1, 1);
        ref.set(lib);
        assertThrows(IllegalStateException.class, () -> lib.checkout("m0", "T0", "north"));
        assertEquals(CopyStatus.ON_HOLD, lib.stateOf("C1").status());
        assertEquals("m1", lib.stateOf("C1").member());
        assertTrue(lib.holdQueue("T0").isEmpty());
        assertEquals(0, lib.openLoans("m0"));
    }

    /** Proves an Error (not just a RuntimeException) thrown after the claim is also undone: the copy, the loan count and the one-title-once set all go back. */
    @Test
    void errorAfterClaimIsAlsoUndone() {
        AtomicInteger calls = new AtomicInteger();
        Hook failOnce = (point, id) -> {
            if (Hook.AFTER_CLAIM.equals(point) && calls.getAndIncrement() == 0) {
                throw new AssertionError("boom");
            }
        };
        InMemoryLibrary lib = build(failOnce, 1, 1, 1);
        assertThrows(AssertionError.class, () -> lib.checkout("m0", "T0", "north"));
        assertEquals(CopyStatus.AVAILABLE, lib.stateOf("C1").status());
        assertEquals(0, lib.openLoans("m0"));
        assertEquals("C1", lib.checkout("m0", "T0", "north").copyId());
    }

    /** Starts the given number of threads behind one start gate so they begin together, and collects every result. */
    private static <T> List<T> runTogether(int threads, Callable<T> task) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
