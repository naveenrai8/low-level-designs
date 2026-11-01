package com.lld.library;

import java.time.Instant;

/**
 * One borrowing of one copy by one member. Immutable: closing a loan removes it from the open set instead of changing it.
 *
 * @param id the loan id
 * @param copyId the borrowed copy
 * @param memberId the borrower
 * @param isbn the title of the copy
 * @param borrowedAt when it was lent, from the clock
 * @param dueAt the instant after which the loan is late (at exactly this instant it is still on time)
 */
public record Loan(String id, String copyId, String memberId, String isbn, Instant borrowedAt, Instant dueAt) {
}
