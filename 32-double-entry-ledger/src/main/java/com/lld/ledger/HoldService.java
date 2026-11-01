package com.lld.ledger;

import java.time.Duration;

/**
 * Holds (authorisations). A hold reserves part of an account's balance without moving it, so the money cannot be spent twice. A later capture moves the money.
 *
 * <p>Error contract: UNKNOWN_ACCOUNT, UNKNOWN_HOLD; ZERO_AMOUNT for an amount of 0 or less; INSUFFICIENT_FUNDS when the hold is larger than the available balance; CAPTURE_TOO_LARGE when a capture exceeds the held amount; HOLD_NOT_ACTIVE for a second capture, a capture after void, or a second void; HOLD_EXPIRED when the time limit passed.
 */
public interface HoldService {

    /**
     * Reserves money on an account with no time limit.
     *
     * @param accountId the account to reserve on
     * @param amount a positive amount in minor units
     * @return the hold id
     */
    String authorize(String accountId, long amount);

    /**
     * Reserves money on an account until the clock passes now plus the time to live.
     *
     * @param accountId the account to reserve on
     * @param amount a positive amount in minor units
     * @param ttl how long the hold lasts
     * @return the hold id
     */
    String authorize(String accountId, long amount, Duration ttl);

    /**
     * Moves the captured amount from the held account to the destination as one posted entry. Whatever is not captured is released, and the hold is finished: a second capture is refused. Retrying a finished capture with the same amount and destination returns the original entry (retry-safe); authorize has no key, so a retried authorize reserves twice.
     *
     * @param holdId the hold
     * @param amount a positive amount, at most the held amount
     * @param toAccountId the account that receives the money (credited if it is a liability, equity or revenue, debited if it is an asset or expense)
     * @return the posted entry
     */
    Entry capture(String holdId, long amount, String toAccountId);

    /**
     * Releases a hold without moving any money.
     *
     * @param holdId the hold
     */
    void voidHold(String holdId);

    /**
     * Tells the state of a hold. A hold whose deadline has passed reads as EXPIRED, because this call applies expiry first.
     *
     * @param holdId the hold
     * @return the state
     */
    HoldState holdState(String holdId);
}
