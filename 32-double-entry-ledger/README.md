# Topic 32: Double-Entry Ledger

"Design the money core of a wallet or payment app." The easy answer is one `balance` column and `UPDATE balance = balance - 100`. The interviewer is waiting for the questions that break it:

1. Where did the money go? A balance column cannot say, and it cannot be audited.
2. Two requests spend the same 100 at the same time. Who wins?
2. The client retries after a timeout. Is the customer charged twice?
3. A card is authorised now and captured later. Can the customer spend the held money in between?

A staff answer starts from a small worked example, makes "every entry sums to zero" impossible to break, and then shows the locking, the retries and the holds with tests that force each race.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
4. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
5. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

Words not defined inline: an **account** is a named bucket of money; a **posting** is one line of an entry that changes one account; an **entry** (journal entry) is a set of postings that happen together; the **journal** is the list of all entries, oldest first; a **projection** is a number kept up to date from the journal so reads are fast.

## 1. The Problem

### Debit and credit in plain words (worked example first)

Every movement of money is written twice: once where it came from, once where it went. That is all "double entry" means. A **debit** is a posting with a plus sign, a **credit** is a posting with a minus sign. Do not read "debit" as "take away": it only means "left side of the book".

Whether a plus makes an account look bigger depends on the kind of account. Each account has a **type** and a **normal side** (the side that makes it grow):

| Type | Meaning | Grows with | Example |
|---|---|---|---|
| ASSET | things we own | debit | cash in our bank |
| EXPENSE | costs | debit | card fees |
| LIABILITY | things we owe | credit | a customer's wallet balance |
| EQUITY | owners' stake | credit | opening capital |
| REVENUE | income | credit | fees we earn |

Worked example: a customer pays 100.00 into their wallet. We now hold 100 more cash, and we now owe the customer 100.

| Step | Account | Posting | Signed amount | Natural balance after |
|---|---|---|---|---|
| 1 | cash (asset) | debit 10,000 | +10,000 | 10,000 (asset grows with debit) |
| 1 | customer-balance (liability) | credit 10,000 | -10,000 | 10,000 (liability grows with credit) |
| sum | | | 0 | |

The customer later spends 30.00 at a shop (we owe the shop now, not the customer): debit customer-balance 3,000, credit shop-balance 3,000. Sum is 0 again. If you add up every account's signed balance, the total is always 0. That one fact is the **global invariant**, and it is the cheapest bug detector in finance.

### What is really being probed

Immutability (history is never edited), integer money, atomic multi-account writes without deadlock, retry safety, and the difference between "posted" money and "available" money.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Money type? | `long` minor units (cents), never `double`. One currency per account. |
| Entry rules? | Two or more postings, one currency, no zero amount, signed amounts sum to exactly 0. |
| Can an entry be edited? | Never. Append only. A correction is a new reversing entry. |
| Can an account go negative? | Not by default (asset, liability and the rest). An account can be flagged `overdraftAllowed` (a bank source). |
| Threads? | Many, on different and on the same accounts. No global lock. |
| Retries? | Yes, callers send an idempotency key. |

Out of scope: a chart of accounts UI, FX rates, tax rules, reconciliation with a bank (see [SystemDesign Topic 98](../../pocs/SystemDesign-poc/ideas.md) for the planned ledger design topic and [Topic 93](../../pocs/SystemDesign-poc/93-payment-flow-lifecycle/README.md) for the payment lifecycle), and persistence.

## 2. How It Solves It

### Entities and invariants

| Entity | Fields | Invariant |
|---|---|---|
| `Account` | id, type, currency, overdraftAllowed | currency never changes |
| `Posting` | accountId, signed amount | amount is never 0 |
| `Draft` | postings, memo | at least 2 postings, sum is exactly 0, sum cannot overflow (checked when built) |
| `Entry` | seq, id, currency, postings, memo, reversesSeq | immutable; `seq` is 1, 2, 3, ... with no gaps |
| `Hold` | id, account, amount, deadline, state | AUTHORIZED moves to exactly one of CAPTURED, VOIDED, EXPIRED |

Global invariants: (1) per currency, the sum of all account balances is 0. (2) A non-overdraft account's available balance (natural balance minus active holds) never goes below 0 through `post`, `authorize` or `capture`; the one exception is a reversal, which skips the funds check so an undo is never blocked. (3) The running balances equal what you get by replaying the journal.

### The interface and the error contract

```java
public interface Ledger {
    void openAccount(Account a);                    // DUPLICATE_ACCOUNT
    Entry post(Draft d);                            // UNKNOWN_ACCOUNT, MIXED_CURRENCY, INSUFFICIENT_FUNDS
    Entry post(Draft d, String idempotencyKey);     // + IDEMPOTENCY_CONFLICT; same key + same draft = same Entry
    Entry reverse(String entryId);                  // UNKNOWN_ENTRY, ALREADY_REVERSED
    long balance(String account);                   // signed: debits minus credits
    long naturalBalance(String account);            // on the account's normal side
    long available(String account);                 // natural balance minus active holds
    long balanceAt(String account, long seq);       // balance just after journal position seq
    long trialBalance(String currency);             // locks the currency's accounts in sorted order; 0 on every read
    Map<String, Long> rebuild();                    // recompute from the journal alone
}
public interface HoldService {
    String authorize(String account, long amount);                 // INSUFFICIENT_FUNDS, ZERO_AMOUNT
    String authorize(String account, long amount, Duration ttl);   // expires by the injected Clock
    Entry capture(String holdId, long amount, String toAccount);   // CAPTURE_TOO_LARGE, HOLD_NOT_ACTIVE, HOLD_EXPIRED; same retry returns the same Entry
    void voidHold(String holdId);                                  // HOLD_NOT_ACTIVE, HOLD_EXPIRED
}
```

One exception type, `LedgerException`, with a `Reason` enum so callers switch on the reason and never parse messages. Badly shaped drafts (UNBALANCED, TOO_FEW_POSTINGS, ZERO_AMOUNT including a draft whose postings cancel out per account, OVERFLOW including `Long.MIN_VALUE`) are rejected when the `Draft` is built, so a bad draft cannot exist. A refused post changes nothing.

### Diagrams

```
 Ledger / HoldService  <--  InMemoryLedger
                              |  accounts: id -> AccountState { lock, balance, held, history[(seq, balanceAfter)] }
                              |  journal : seq -> Entry          (append only)
                              |  holds   : id  -> Hold
                              |  slots   : idempotencyKey -> (Draft, Entry)   (bounded)
 Draft ---has many---> Posting(accountId, signedAmount)         Entry = Draft + seq + id (immutable)

 (Two transfers A->B and B->A both sort their ids to [A, B] and lock A first, so one waits: see the timeline below.)

 authorize -> capture:
 client           HoldService/Ledger                      accounts
   |-- authorize(W, 400) -->| lock W; available >= 400?   held(W) += 400   (no entry, posted balance unchanged)
   |<-- holdId -------------|
   |-- capture(h, 250, M) ->| lock W and M (sorted); hold still AUTHORIZED?
   |                        | held(W) -= 400 (whole hold ends); post entry W->M 250
   |<-- Entry --------------|  the other 150 is simply available again; a second capture -> HOLD_NOT_ACTIVE
```

### The algorithm: post one entry

Timeline for two transfers on accounts A and B, each holding 1000 (liability type, so natural balance = credit side):

| Step | What happens | Result |
|---|---|---|
| 1 | T1 asks A->B 600, T2 asks A->B 600 at the same moment | both want lock A first (sorted order) |
| 2 | T1 gets lock A then lock B; T2 waits | T1 sees A=1000, check passes |
| 3 | T1 appends entry 3, A=400, B=1600, unlocks | T2 now gets the locks |
| 4 | T2 reads A=400, the check fails | INSUFFICIENT_FUNDS, nothing written |

The steps, with cost for an entry of k postings touching m accounts:

```
post(draft):
  merge postings per account, verify accounts exist and share one currency      O(k)
  sort the m account ids, lock them in that order                                O(m log m)
  for each account: newBalance = balance + delta; if it drops below 0 (after holds) -> refuse
  seq = counter.incrementAndGet(); journal.put(seq, entry)
  for each account: balance = newBalance; history.add((seq, newBalance))
  unlock in reverse order
```

Cost is O(k + m log m) time and O(k) space per entry, plus about 40 bytes of history per touched account (a two-long array plus a list slot, an estimate) (so `balanceAt` is a binary search, O(log h)). Reading one balance takes one account lock and never blocks unrelated accounts. `trialBalance`, `journal` and `rebuild` lock all the accounts they read (sorted order) so they never see a post half done; they are audit calls, not hot-path reads.

Reversal: `reverse(id)` builds the same postings with every sign flipped and posts it with `reversesSeq` set. It skips the funds check, because undoing a posting must never be blocked (the test `reversalMayOverdraw` shows this). The original is untouched.

### The concurrency model

- **Shared state:** one balance, one `held` total and one history list per account. The journal and the sequence counter are shared too.
- **Invariant:** a posting's funds check and its write happen under the same account locks, so no other writer can change that balance in between.
- **Primitive:** one `ReentrantLock` per account, taken in sorted account-id order (the rule from [Topic 08](../08-multi-object-atomicity/README.md)), plus one `AtomicLong` for the sequence. No global lock, so transfers between disjoint pairs run in parallel.
- **Why sorted:** if T1 takes A then B and T2 takes B then A, each waits for the other forever. A single global order makes a cycle impossible. Background: [deadlock, livelock, starvation](../../internal-pocs/Java-poc/concurrency/14-deadlock-livelock-starvation/) and [ReentrantLock](../../internal-pocs/Java-poc/concurrency/16-reentrantlock/).
- **Ordering note:** seq numbers are taken while the account locks are held, so two entries on the same account get seq in the same order as their balance changes. Entries on different accounts may be journalled slightly out of lock order, which is fine because the journal is ordered by seq.

### Bugs found by the tests (each has a test that shows the defect)

| Bug class | Broken code | Test that shows it | Fix |
|---|---|---|---|
| Lock-order inversion (deadlock) | `LockOrder.AS_WRITTEN` locks in posting order | `lockOrderInversionDeadlocks` (each thread waits after its first lock until the other holds one; the JVM's `findDeadlockedThreads` reports the deadlock) | `LockOrder.SORTED`, `sortedLockOrderNeverDeadlocks` |
| Check-then-act race (time-of-check to time-of-use) | `RacyBalanceBook.spend` reads the balance, calls a hook, then writes | `checkThenActRaceOverdraws`: the hook runs a full second spend between check and write, balance ends at -100 | check and write under the account lock, `fixedLedgerClosesTheRace` (the test waits until the second spender is queued on the lock via a probe, then exactly one wins) |

### Code and tests (54 tests, about 0.9 s of test time, 2.5 to 3 s wall with Maven)

| File | What it shows |
|---|---|
| `AccountType`, `Account`, `Posting`, `Draft`, `Entry` | immutable value types; `Draft` rejects bad shapes at construction |
| `Ledger`, `HoldService`, `LedgerException` | interface first, with the error contract in Javadoc |
| `InMemoryLedger` | per-account locks in sorted order, projections, holds, idempotency |
| `RacyBalanceBook`, `LockOrder`, `Gap` | the deliberately broken variants and the interleaving hook |
| `LedgerBasicsTest` (18) | worked example, every rejection, immutability, reversal |
| `InvariantTest` (1) | 6000 seeded random operations: sum is 0 per currency, projection equals rebuild, seq has no gaps |
| `HoldTest` (17) | F1: authorise, partial capture, void, expiry via a fake `Clock` |
| `IdempotencyTest` (5) | F2: replays, conflict, retry after failure, bounded memory (k1..k3 still replay) |
| `ConcurrencyTest` (10) | deadlock (JVM-reported) vs sorted order, capture vs post, 32-thread hammer, readers see 0 on every read, same-key racers, check-then-act race with a proven-blocked rival |
| `ProjectionTest` (3) | F3: differential `rebuild()`, `balanceAt` vs replay |

## 3. The Gaps

### F1: holds (authorisations) versus captures (coded)

A card payment is first **authorised** (the bank promises to keep the money) and **captured** later when the goods ship. In between the money has not moved, but it must not be spendable. We track a `held` total per account and define **available = natural balance - held**. `authorize` refuses when available is too small; `post` also checks against available, so held money cannot be spent by another entry (`heldMoneyCannotBeSpentByAnotherEntry`).

| Time | Action | Natural balance | Held | Available |
|---|---|---|---|---|
| 0 | wallet has 1000 | 1000 | 0 | 1000 |
| 1 | authorize 400 | 1000 | 400 | 600 |
| 2 | capture 250 to the shop | 750 | 0 | 750 |

At step 2 the whole hold ends; the unused 150 is released at once. A second capture, a capture after void, and a capture above the held amount are all refused. A hold with a time limit expires when the injected `Clock` passes its deadline; expiry is applied lazily whenever the account is touched, so there is no background thread to test. A retried capture (same amount and destination) returns the original entry. `authorize` has no key, so a retried authorize reserves twice: a known gap (production adds a client reference to the hold and a unique index on it).

### F2: idempotent posting (coded)

`post(draft, key)` stores `key -> (draft, entry)`. Same key and equal draft returns the original entry; a different draft with the same key throws `IDEMPOTENCY_CONFLICT`. The check and the post happen inside one monitor per key, so 16 concurrent retries post once (`sameKeyRacersPostOnce`). A failed post is not remembered, so the client can retry. The map remembers the newest 100,000 finished keys; older ones are forgotten, so a very late retry would post again. In production the key is a unique-indexed column on the entry row, written in the same database transaction, so it is never forgotten. Key design is in [Topic 11](../11-money-ids-idempotency/README.md) and [event-processing idempotency](../../internal-pocs/event-processing-poc/core/09-idempotency-dedup/README.md).

### F3: balance projections and audit without global locks (coded, small)

Each account keeps a running balance and a history of `(seq, balanceAfter)` pairs, updated inside the same critical section as the append. So a read is O(1) and `balanceAt(account, seq)` is a binary search. `rebuild()` replays the journal and must equal the projection; `projectionEqualsRebuild` checks that after 3000 random operations. Design delta for production: - Give every entry a **sequence number** (the audit clock), take a **snapshot** of all balances every N entries (say 100,000) so a rebuild replays only the tail, and keep **append-only storage** (no UPDATE or DELETE rights) plus a separate **read model** for balances that can be rebuilt from the journal.

### Misconceptions and common mistakes

- **Floating-point money.** `0.1 + 0.2 != 0.3`, and sums drift. Use `long` cents and compare exactly. See [Topic 11](../11-money-ids-idempotency/README.md).
- **Editing an entry to fix a mistake.** Audits need reproducible history. Add a reversing entry and a new correct entry.
- **A single `balance` column ("single-entry").** It cannot say where money came from, cannot be checked against anything, and a lost update silently creates or destroys money. With double entry the total of all accounts must be 0, so any bug shows up.
- **"Negative zero-sum" confusion.** A credit is a negative number, but a liability is not "overdrawn": its natural balance is the negative of its signed balance. Check the natural balance.
- **Several currencies in one entry.** Rejected (`MIXED_CURRENCY`), because the sum of 100 USD and -90 EUR is meaningless. An FX conversion is two entries linked by an **FX account pair**: in USD, debit customer-USD, credit fx-USD; in EUR, debit fx-EUR, credit customer-EUR. Each currency balances alone, and the FX accounts hold our currency exposure.
- **Counting held money as available**, or applying a hold only in the UI.

### Production and distributed delta

- **Hot account** (a fee account every payment touches): per-account locks serialise it. Batch its postings, shard it into sub-accounts summed on read, or use a version column. A plain points counter nobody audits does not need any of this.
- **Database transaction:** `BEGIN; SELECT ... FROM accounts WHERE id IN (...) ORDER BY id FOR UPDATE;` (the same sorted order, now enforced by the database), check funds, `INSERT` the entry and postings, `UPDATE` the balances, `COMMIT`. Or keep a `version` column and `UPDATE ... WHERE version = ?`, retrying on a miss. See [Postgres transactions and isolation](../../pocs/Postgres-poc/10_transactions/README.md) (locking and deadlocks are scripts 03 and 05 there) and [distributed transactions](../../pocs/Postgres-poc/26_distributed_transactions/README.md).
- **Ledger as an append-only table** with a unique index on the idempotency key and `CHECK` or a deferred trigger that postings per entry sum to 0; revoke UPDATE and DELETE. See [audit and temporal tables](../../pocs/Postgres-poc/28_audit_temporal/README.md).
- **Outbox:** write an event row in the same transaction as the entry, and publish it from there, so downstream systems (notifications, reporting) never see an entry that was rolled back. See [outbox and CDC](../../internal-pocs/event-processing-poc/core/16-outbox-cdc/README.md).

## 4. Official References

- [Martin Fowler, Accounting Narrative](https://martinfowler.com/eaaDev/AccountingNarrative.html), [Account](https://martinfowler.com/eaaDev/Account.html), [Accounting Transaction](https://martinfowler.com/eaaDev/AccountingTransaction.html), [Accounting Entry](https://martinfowler.com/eaaDev/AccountingEntry.html) and [Quantity](https://martinfowler.com/eaaDev/Quantity.html) (analysis patterns)
- Books: Martin Fowler, *Analysis Patterns*, ch. 6 "Inventory and Accounting" and ch. 7 "Using the Accounting Models"; *Designing Data-Intensive Applications*, 1st edition, ch. 7 "Transactions" and ch. 11 "Stream Processing" (section "State, Streams, and Immutability")
- [Square: Books, an immutable double-entry accounting database service](https://developer.squareup.com/blog/books-an-immutable-double-entry-accounting-database-service/)
- [Stripe: Ledger, a system for tracking and validating money movement](https://stripe.com/blog/ledger-stripe-system-for-tracking-and-validating-money-movement)
- [Modern Treasury: Accounting for Developers, Part I](https://www.moderntreasury.com/journal/accounting-for-developers-part-i) and [What is a ledger](https://www.moderntreasury.com/learn/what-is-a-ledger)
- [Stripe docs: place a hold on a payment method](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method) (authorise now, capture later, partial capture)
- [PostgreSQL: explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html) (`SELECT ... FOR UPDATE`, deadlocks)

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-5 | Clarify: currencies, can accounts go negative, holds, retries, scale. State out of scope. |
| 5-10 | Draw the 100-dollar example: two postings that sum to 0. Explain debit, credit and account types in one minute. |
| 10-20 | Write `Draft` (sum to zero checked at construction), `Posting`, `Entry`, the interface and the error contract. |
| 20-30 | `post`: per-account locks in sorted order, check then write under the lock, reversal instead of edit. |
| 30-37 | Concurrency tests: opposite transfers with a probe, the hammer, the check-then-write race. |
| 37-42 | Holds (available = balance - held, partial capture) and idempotency keys. |
| 42-45 | Projections vs journal, snapshots, then the production delta (SELECT FOR UPDATE, outbox). |

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Model | Account with a balance, a transfer method | Double entry, entries sum to 0 by construction, global trial-balance invariant tested over thousands of random operations |
| Corrections and money | Update the row; `long` cents | Immutable entries, reversing entry not blocked by funds checks, one currency per entry, FX pair, overflow-checked sums |
| Concurrency | `synchronized` transfer | Per-account locks in sorted order, deadlock shown by a test with a barrier, check-then-act race shown and fixed |
| Retries and holds | "Use a UUID"; subtract at once | Key plus draft equality, conflict on mismatch, bounded memory, production unique index; available vs posted, partial capture, no double capture, expiry via an injected clock |
| Audit and production | Log lines; "use a database" | Sequence numbers, `balanceAt`, rebuild equals projection, snapshots; `FOR UPDATE` in id order or version columns, outbox, hot-account strategies |

## 7. Key Takeaways

1. Every movement is two or more signed postings that sum to exactly 0, built into the `Draft` type, so the sum of all balances stays 0 forever. History is append-only: a mistake is fixed by a reversing entry, never an edit.
3. Lock each touched account in sorted id order and do the funds check inside the lock: that kills both the deadlock and the double spend.
4. Available = posted - holds. A capture ends its hold and releases the rest; a hold ends in exactly one state.
5. Idempotency: same key and same draft returns the same entry; same key and a different draft is a conflict. In production the key lives in a unique index next to the entry.
6. Keep running balances as a projection, check it against `rebuild()`, and use sequence numbers and snapshots for audits.

**Self-grade: STRONG.** Full spine, worked example before code, interface and error contract first, 54 tests including a 6000-operation seeded invariant test, a differential rebuild test, a JVM-reported deadlock and a forced check-then-act race with their fixes, and 12 hand mutations that the suite kills. Gaps: `authorize` has no idempotency key (a retried authorize reserves twice), the idempotency map forgets old keys (production uses a unique index), finished holds are kept in memory, FX pairs, snapshots and persistence are design-only, and hot-account strategies are not coded.

[← Topic 31: Seat-Hold Booking](../31-seat-hold-booking/) · [Index](../README.md) · [Topic 33: Splitwise →](../33-splitwise/)
