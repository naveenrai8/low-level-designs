package com.lld.ledger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An entry that has not been posted yet: two or more postings that sum to exactly zero, plus a memo.
 * <p>The shape rules (at least two postings, sum is zero, no overflow) are checked here, so a bad draft cannot even be built. The rules that need the accounts (they exist, one currency) are checked by the ledger.
 * <p>Two drafts are equal when their postings and memo are equal. The idempotency check uses this.
 *
 * @param postings the lines, copied so later changes to the caller's list do not leak in
 * @param memo a free-text note
 */
public record Draft(List<Posting> postings, String memo) {

    /**
     * Copies the postings and checks the shape. Throws {@link LedgerException} with TOO_FEW_POSTINGS, OVERFLOW, ZERO_AMOUNT (every account nets to zero) or UNBALANCED.
     */
    public Draft {
        postings = List.copyOf(postings);
        memo = memo == null ? "" : memo;
        if (postings.size() < 2) {
            throw new LedgerException(LedgerException.Reason.TOO_FEW_POSTINGS, "an entry needs at least two postings");
        }
        long sum = 0;
        for (Posting p : postings) {
            try {
                sum = Math.addExact(sum, p.amount());
            } catch (ArithmeticException e) {
                throw new LedgerException(LedgerException.Reason.OVERFLOW, "posting amounts overflow a long");
            }
        }
        Map<String, Long> perAccount = new HashMap<>();
        for (Posting p : postings) {
            perAccount.merge(p.accountId(), p.amount(), Long::sum);
        }
        if (perAccount.values().stream().allMatch(v -> v == 0)) {
            throw new LedgerException(LedgerException.Reason.ZERO_AMOUNT, "postings cancel out per account, so nothing would move");
        }
        if (sum != 0) {
            throw new LedgerException(LedgerException.Reason.UNBALANCED, "postings sum to " + sum + ", not zero");
        }
    }

    /**
     * Builds a draft from postings written inline.
     *
     * @param memo a note
     * @param postings the lines
     * @return the draft
     */
    public static Draft of(String memo, Posting... postings) {
        return new Draft(List.of(postings), memo);
    }

    /**
     * Builds the simplest draft: debit one account and credit another by the same amount.
     *
     * @param debitAccount the account to debit
     * @param creditAccount the account to credit
     * @param amount a positive amount in minor units
     * @param memo a note
     * @return the draft
     */
    public static Draft move(String debitAccount, String creditAccount, long amount, String memo) {
        return of(memo, Posting.debit(debitAccount, amount), Posting.credit(creditAccount, amount));
    }

    /**
     * Builds the mirror image of this draft (every sign flipped), used for reversals.
     *
     * @param memo a note for the new draft
     * @return the negated draft
     */
    public Draft negated(String memo) {
        return new Draft(postings.stream().map(p -> new Posting(p.accountId(), Math.negateExact(p.amount()))).toList(), memo);
    }
}
