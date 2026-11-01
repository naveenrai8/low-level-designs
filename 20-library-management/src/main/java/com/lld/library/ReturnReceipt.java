package com.lld.library;

import java.time.Instant;

/**
 * What a member gets back when a loan is closed.
 *
 * @param loanId the closed loan
 * @param returnedAt the clock time of the return
 * @param daysLate whole days late, rounded up (0 when on time)
 * @param fineMinor the fine in minor units (cents), already capped
 */
public record ReturnReceipt(String loanId, Instant returnedAt, long daysLate, long fineMinor) {
}
