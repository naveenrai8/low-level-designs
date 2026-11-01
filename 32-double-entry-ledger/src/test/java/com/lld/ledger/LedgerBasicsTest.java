package com.lld.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lld.ledger.LedgerException.Reason;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The v1 rules: balanced entries, account types, rejections, and immutability with reversing entries. */
class LedgerBasicsTest {

    private InMemoryLedger ledger;

    /** Opens the accounts of the worked example: cash (asset), customer-balance (liability), revenue, and a EUR account. */
    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger(new TestClock());
        ledger.openAccount(Account.of("cash", AccountType.ASSET, "USD"));
        ledger.openAccount(Account.of("cust", AccountType.LIABILITY, "USD"));
        ledger.openAccount(Account.of("rev", AccountType.REVENUE, "USD"));
        ledger.openAccount(Account.of("eur", AccountType.ASSET, "EUR"));
    }

    /** Asserts that the action throws a ledger error with the given reason. */
    private static void rejects(Reason reason, org.junit.jupiter.api.function.Executable action) {
        assertEquals(reason, assertThrows(LedgerException.class, action).reason());
    }

    /** The worked example: a customer pays 100, cash goes up by a debit and the customer balance (a liability) goes up by a credit. */
    @Test
    void customerPaysOneHundred() {
        Entry e = ledger.post(Draft.move("cash", "cust", 10_000, "customer pays"));
        assertEquals(1, e.seq());
        assertEquals(10_000, ledger.naturalBalance("cash"));
        assertEquals(10_000, ledger.naturalBalance("cust"));
        assertEquals(10_000, ledger.balance("cash"));
        assertEquals(-10_000, ledger.balance("cust"));
        assertEquals(0, ledger.trialBalance("USD"));
    }

    /** Postings that do not add up to zero cannot even form a draft. */
    @Test
    void unbalancedDraftIsRejected() {
        rejects(Reason.UNBALANCED, () -> Draft.of("x", Posting.debit("cash", 100), Posting.credit("cust", 99)));
    }

    /** A draft with a single posting is rejected, because one line can never balance. */
    @Test
    void singlePostingIsRejected() {
        rejects(Reason.TOO_FEW_POSTINGS, () -> Draft.of("x", new Posting("cash", 5)));
    }

    /** A posting of amount zero is rejected, and so is a non-positive debit or credit helper amount. */
    @Test
    void zeroAmountIsRejected() {
        rejects(Reason.ZERO_AMOUNT, () -> new Posting("cash", 0));
        rejects(Reason.ZERO_AMOUNT, () -> Posting.debit("cash", 0));
        rejects(Reason.ZERO_AMOUNT, () -> Posting.credit("cash", -5));
    }

    /** Postings whose amounts would overflow a long while summing are rejected, not silently wrapped. */
    @Test
    void overflowingSumIsRejected() {
        rejects(Reason.OVERFLOW, () -> Draft.of("x", new Posting("a", Long.MAX_VALUE), new Posting("b", Long.MAX_VALUE), new Posting("c", 2)));
    }

    /** An entry that touches accounts of two currencies is refused, and nothing is posted. */
    @Test
    void mixedCurrencyIsRejected() {
        rejects(Reason.MIXED_CURRENCY, () -> ledger.post(Draft.move("cash", "eur", 100, "fx")));
        assertTrue(ledger.journal().isEmpty());
    }

    /** An entry that names a missing account is refused, and nothing is posted. */
    @Test
    void unknownAccountIsRejected() {
        rejects(Reason.UNKNOWN_ACCOUNT, () -> ledger.post(Draft.move("cash", "nope", 100, "x")));
        assertTrue(ledger.journal().isEmpty());
    }

    /** Opening the same account twice fails. */
    @Test
    void duplicateAccountIsRejected() {
        rejects(Reason.DUPLICATE_ACCOUNT, () -> ledger.openAccount(Account.of("cash", AccountType.ASSET, "USD")));
    }

    /** An asset cannot go below zero unless it is marked overdraft; the failed entry leaves every balance unchanged. */
    @Test
    void insufficientFundsLeavesNothingChanged() {
        ledger.post(Draft.move("cash", "cust", 500, "in"));
        rejects(Reason.INSUFFICIENT_FUNDS, () -> ledger.post(Draft.move("cust", "cash", 800, "out")));
        assertEquals(500, ledger.naturalBalance("cash"));
        assertEquals(1, ledger.journal().size());
    }

    /** A three-line entry (price, tax split) balances and updates every account. */
    @Test
    void multiLineEntryWorks() {
        ledger.openAccount(Account.of("tax", AccountType.LIABILITY, "USD"));
        ledger.post(Draft.of("sale with tax", Posting.debit("cash", 1_100), Posting.credit("rev", 1_000), Posting.credit("tax", 100)));
        assertEquals(1_000, ledger.naturalBalance("rev"));
        assertEquals(100, ledger.naturalBalance("tax"));
        assertEquals(0, ledger.trialBalance("USD"));
    }

    /** The journal and the postings inside an entry are read-only, so posted history cannot be edited. */
    @Test
    void entriesAreImmutable() {
        Entry e = ledger.post(Draft.move("cash", "cust", 100, "in"));
        assertThrows(UnsupportedOperationException.class, () -> e.postings().add(new Posting("cash", 1)));
        assertThrows(UnsupportedOperationException.class, () -> ledger.journal().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.journal().get(0).postings().clear());
    }

    /** The draft copies the caller's list, so changing the list afterwards cannot change what was posted. */
    @Test
    void draftIsCopiedFromTheCallersList() {
        List<Posting> mine = new java.util.ArrayList<>(List.of(Posting.debit("cash", 100), Posting.credit("cust", 100)));
        Draft d = new Draft(mine, "x");
        mine.clear();
        assertEquals(2, ledger.post(d).postings().size());
    }

    /** A correction is a new reversing entry: the original stays, balances return to zero, and the journal only grows. */
    @Test
    void correctionIsAReversingEntry() {
        Entry e = ledger.post(Draft.move("cash", "cust", 700, "typo"));
        Entry r = ledger.reverse(e.id());
        assertEquals(2, ledger.journal().size());
        assertEquals(e, ledger.journal().get(0));
        assertEquals(e.seq(), r.reversesSeq());
        assertEquals(0, ledger.balance("cash"));
        assertEquals(0, ledger.balance("cust"));
    }

    /** Reversing twice, or reversing an unknown id, is refused. */
    @Test
    void reversalChecks() {
        Entry e = ledger.post(Draft.move("cash", "cust", 700, "typo"));
        ledger.reverse(e.id());
        rejects(Reason.ALREADY_REVERSED, () -> ledger.reverse(e.id()));
        rejects(Reason.UNKNOWN_ENTRY, () -> ledger.reverse("e999"));
    }

    /** Reversing money that has already been spent still works (it may overdraw), because undoing must not be blocked. */
    @Test
    void reversalMayOverdraw() {
        Entry in = ledger.post(Draft.move("cash", "cust", 500, "in"));
        ledger.post(Draft.move("cust", "cash", 500, "back"));
        ledger.reverse(in.id());
        assertEquals(-500, ledger.naturalBalance("cash"));
    }

    /** Balance arithmetic that would overflow a long is refused with OVERFLOW and changes nothing. */
    @Test
    void balanceOverflowIsRejected() {
        ledger.openAccount(Account.overdraft("big", AccountType.EQUITY, "USD"));
        ledger.post(Draft.move("cash", "big", Long.MAX_VALUE, "max"));
        int size = ledger.journal().size();
        rejects(Reason.OVERFLOW, () -> ledger.post(Draft.move("cash", "big", 1, "one more")));
        assertEquals(Long.MAX_VALUE, ledger.balance("cash"));
        assertEquals(size, ledger.journal().size());
    }

    /** Long.MIN_VALUE cannot be a posting, because its mirror image for a reversal does not exist. */
    @Test
    void minValuePostingIsRejected() {
        rejects(Reason.OVERFLOW, () -> new Posting("cash", Long.MIN_VALUE));
    }

    /** A draft whose postings cancel out on every account moves nothing and is refused. */
    @Test
    void selfCancellingDraftIsRejected() {
        rejects(Reason.ZERO_AMOUNT, () -> Draft.of("x", new Posting("cash", 5), new Posting("cash", -5)));
    }
}
