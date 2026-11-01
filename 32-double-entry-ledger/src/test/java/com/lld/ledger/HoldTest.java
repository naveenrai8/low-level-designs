package com.lld.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lld.ledger.LedgerException.Reason;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** F1: authorisations reserve money, captures move it, and the rest is released. */
class HoldTest {

    private TestClock clock;
    private InMemoryLedger ledger;

    /** Opens a customer wallet (liability) holding 1000, and a merchant account. */
    @BeforeEach
    void setUp() {
        clock = new TestClock();
        ledger = new InMemoryLedger(clock);
        ledger.openAccount(Account.of("wallet", AccountType.LIABILITY, "USD"));
        ledger.openAccount(Account.of("merchant", AccountType.LIABILITY, "USD"));
        ledger.openAccount(Account.of("wallet2", AccountType.LIABILITY, "USD"));
        ledger.openAccount(Account.overdraft("bank", AccountType.ASSET, "USD"));
        ledger.post(Draft.move("bank", "wallet", 1_000, "top up"));
    }

    /** Asserts that the action throws a ledger error with the given reason. */
    private static void rejects(Reason reason, Executable action) {
        assertEquals(reason, assertThrows(LedgerException.class, action).reason());
    }

    /** A hold lowers the available balance but leaves the posted balance and the journal alone. */
    @Test
    void holdReducesAvailableNotPosted() {
        ledger.authorize("wallet", 400);
        assertEquals(600, ledger.available("wallet"));
        assertEquals(1_000, ledger.naturalBalance("wallet"));
        assertEquals(1, ledger.journal().size());
    }

    /** You cannot authorise more than what is available, counting earlier holds. */
    @Test
    void cannotAuthorizeBeyondAvailable() {
        ledger.authorize("wallet", 700);
        rejects(Reason.INSUFFICIENT_FUNDS, () -> ledger.authorize("wallet", 301));
        ledger.authorize("wallet", 300);
        assertEquals(0, ledger.available("wallet"));
    }

    /** A plain post cannot spend money that is held. */
    @Test
    void heldMoneyCannotBeSpentByAnotherEntry() {
        ledger.authorize("wallet", 900);
        rejects(Reason.INSUFFICIENT_FUNDS, () -> ledger.post(Draft.move("wallet", "merchant", 200, "spend")));
        ledger.post(Draft.move("wallet", "merchant", 100, "spend"));
        assertEquals(0, ledger.available("wallet"));
    }

    /** A partial capture moves only the captured amount and releases the remainder. */
    @Test
    void partialCaptureReleasesTheRest() {
        String h = ledger.authorize("wallet", 400);
        Entry e = ledger.capture(h, 250, "merchant");
        assertEquals(750, ledger.naturalBalance("wallet"));
        assertEquals(750, ledger.available("wallet"));
        assertEquals(250, ledger.naturalBalance("merchant"));
        assertEquals(HoldState.CAPTURED, ledger.holdState(h));
        assertEquals(2, e.seq());
        assertEquals(0, ledger.trialBalance("USD"));
    }

    /** Capturing more than was held is refused and the hold stays active. */
    @Test
    void captureAboveHeldIsRejected() {
        String h = ledger.authorize("wallet", 400);
        rejects(Reason.CAPTURE_TOO_LARGE, () -> ledger.capture(h, 401, "merchant"));
        assertEquals(HoldState.AUTHORIZED, ledger.holdState(h));
        assertEquals(600, ledger.available("wallet"));
    }

    /** A second capture of the same hold is refused, so the merchant is paid once. */
    @Test
    void doubleCaptureIsRejected() {
        String h = ledger.authorize("wallet", 400);
        ledger.capture(h, 100, "merchant");
        rejects(Reason.HOLD_NOT_ACTIVE, () -> ledger.capture(h, 150, "merchant"));
        rejects(Reason.HOLD_NOT_ACTIVE, () -> ledger.capture(h, 100, "wallet2"));
        assertEquals(100, ledger.naturalBalance("merchant"));
    }

    /** Void frees the money; a capture or a second void after it is refused. */
    @Test
    void voidThenCaptureIsRejected() {
        String h = ledger.authorize("wallet", 400);
        ledger.voidHold(h);
        assertEquals(1_000, ledger.available("wallet"));
        rejects(Reason.HOLD_NOT_ACTIVE, () -> ledger.capture(h, 100, "merchant"));
        rejects(Reason.HOLD_NOT_ACTIVE, () -> ledger.voidHold(h));
    }

    /** Unknown hold ids and non-positive amounts are refused. */
    @Test
    void badArguments() {
        rejects(Reason.UNKNOWN_HOLD, () -> ledger.voidHold("nope"));
        rejects(Reason.ZERO_AMOUNT, () -> ledger.authorize("wallet", 0));
        String h = ledger.authorize("wallet", 10);
        rejects(Reason.ZERO_AMOUNT, () -> ledger.capture(h, 0, "merchant"));
        rejects(Reason.UNKNOWN_ACCOUNT, () -> ledger.capture(h, 5, "ghost"));
        assertEquals(HoldState.AUTHORIZED, ledger.holdState(h));
    }

    /** A hold with a time limit frees its money once the injected clock passes the deadline, and a late capture is refused. */
    @Test
    void holdExpires() {
        String h = ledger.authorize("wallet", 400, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(4));
        assertEquals(600, ledger.available("wallet"));
        clock.advance(Duration.ofMinutes(1));
        assertEquals(1_000, ledger.available("wallet"));
        assertEquals(HoldState.EXPIRED, ledger.holdState(h));
        rejects(Reason.HOLD_EXPIRED, () -> ledger.capture(h, 100, "merchant"));
    }

    /** Capturing from an asset account credits it (cash leaves) and debits the destination asset, so the signs follow the account type. */
    @Test
    void captureFromAnAssetAccountCreditsIt() {
        ledger.openAccount(Account.of("till", AccountType.ASSET, "USD"));
        ledger.openAccount(Account.of("safe", AccountType.ASSET, "USD"));
        ledger.post(Draft.move("till", "wallet", 300, "cash in"));
        String h = ledger.authorize("till", 300);
        ledger.capture(h, 300, "safe");
        assertEquals(0, ledger.naturalBalance("till"));
        assertEquals(300, ledger.naturalBalance("safe"));
    }

    /** A client that retries a capture that already succeeded (same amount, same destination) gets the original entry back and no second posting. */
    @Test
    void retriedCaptureReturnsTheOriginalEntry() {
        String h = ledger.authorize("wallet", 400);
        Entry first = ledger.capture(h, 250, "merchant");
        int size = ledger.journal().size();
        assertEquals(first, ledger.capture(h, 250, "merchant"));
        assertEquals(size, ledger.journal().size());
        assertEquals(250, ledger.naturalBalance("merchant"));
    }

    /** A capture whose posting fails (the funds were reversed away) leaves the hold exactly as it was: still AUTHORIZED, same available, journal unchanged. */
    @Test
    void failedCaptureRestoresTheHold() {
        String h = ledger.authorize("wallet", 400);
        ledger.reverse("e1");
        long availableBefore = ledger.available("wallet");
        int size = ledger.journal().size();
        rejects(Reason.INSUFFICIENT_FUNDS, () -> ledger.capture(h, 250, "merchant"));
        assertEquals(HoldState.AUTHORIZED, ledger.holdState(h));
        assertEquals(availableBefore, ledger.available("wallet"));
        assertEquals(size, ledger.journal().size());
    }

    /** Capturing exactly at the deadline is too late, and capture itself applies expiry (no earlier read needed). */
    @Test
    void captureAtTheDeadlineIsExpired() {
        String h = ledger.authorize("wallet", 400, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(5));
        rejects(Reason.HOLD_EXPIRED, () -> ledger.capture(h, 100, "merchant"));
    }

    /** A plain post applies expiry itself: an expired hold no longer blocks spending, with no earlier read. */
    @Test
    void postSeesExpiredHoldAsFree() {
        ledger.authorize("wallet", 900, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(6));
        ledger.post(Draft.move("wallet", "merchant", 200, "spend"));
        assertEquals(800, ledger.naturalBalance("wallet"));
    }

    /** Authorize applies expiry itself: after the old hold lapses the full balance can be reserved again. */
    @Test
    void authorizeSeesExpiredHoldAsFree() {
        ledger.authorize("wallet", 1_000, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(6));
        ledger.authorize("wallet", 1_000);
        assertEquals(0, ledger.available("wallet"));
    }

    /** Void applies expiry itself: voiding a lapsed hold says HOLD_EXPIRED. */
    @Test
    void voidOfAnExpiredHoldIsRejected() {
        String h = ledger.authorize("wallet", 400, Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(6));
        rejects(Reason.HOLD_EXPIRED, () -> ledger.voidHold(h));
    }

    /** Capturing into the held account itself is refused. */
    @Test
    void captureIntoTheSameAccountIsRejected() {
        String h = ledger.authorize("wallet", 400);
        rejects(Reason.BAD_ARGUMENT, () -> ledger.capture(h, 5, "wallet"));
    }
}
