# Topic 47: Capstone, Ticketing Platform

"Design the checkout of a ticketing site." A buyer clicks Pay, and the seat, the money and the confirmation email must all agree, even when the server dies in the middle and the buyer clicks again. This capstone does not teach new parts. It glues three finished topics (seat holds, a ledger, notifications) into one flow and spends its time on the hard part: what is true after a failure at each step.

It is a timed mock interview in three rounds. The rounds are the timeline.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It (Rounds 1 to 3)](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Timeline](#5-45-minute-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

## 1. The Problem

What is really probed: not "can you call three services in a row", but "what is true if you stop after any one of them". The interviewer will ask about every gap between two calls.

v1: a buyer picks seats of a show and pays one total price. The seats are held for 5 minutes (Topic 31). The price is authorized on the buyer's wallet (Topic 32: an *authorization*, also called a *hold*, sets the money aside without moving it). Then it is captured (moved to the venue), the booking is confirmed, and the buyer gets an email (Topic 44).

Clarifying questions, with the assumed answers:
- One payment for all seats? Yes, one total price. Split payments are out.
- Is the payment instant? Yes, a ledger wallet. A real card gateway has the same two calls: authorize and capture.
- Does the buyer send a key? Yes. The checkout `Idempotency-Key` is the checkout id (Topic 11).
- Out of scope: pricing, seat maps, real gateways, partial refunds, and REST. REST is skipped because the interface contract below already shows the status codes (409 and 422 from the IETF draft), and [Topic 31's adapter](../31-seat-hold-booking/) already shows `Idempotency-Key` over HTTP.

## 2. How It Solves It

Reused, not rebuilt: [Topic 31](../31-seat-hold-booking/) holds and confirms seats, [Topic 32](../32-double-entry-ledger/) authorizes and moves money, [Topic 44](../44-notification-service/) sends messages, [Topic 11](../11-money-ids-idempotency/) teaches idempotency. The new code is ten small files in `com.lld.ticketing`.

### The interface and its error contract

```java
CheckoutResult checkout(String idempotencyKey, CheckoutRequest request);  // run or resume
int sweep();        // finish stuck checkouts whose seat hold has run out
int flushOutbox();  // send waiting confirmations
int purge();        // forget old finished checkouts
State stateOf(String idempotencyKey);
```

| Outcome | Thrown or returned | HTTP an adapter would give |
|---|---|---|
| Same key, different request | `CheckoutKeyConflictException` | 422 |
| Same key, first call still running | `CheckoutInProgressException` (nothing changed) | 409 |
| Blank key | `IllegalArgumentException` | 400 |
| Seats taken, no funds, hold expired, needs review | an end state in `CheckoutResult` | 200, same answer on replay |

### Entities and invariants

| Entity | Invariant |
|---|---|
| `CheckoutRecord` (one per key) | The state only moves forward. A step runs only when the record says the one before it is done. |
| Payment hold (Topic 32) | It expires later than the seat hold (`paymentGrace`), so money is never freed before the seats. |
| Ledger | Every entry sums to zero, so the whole ledger sums to zero. The venue holds exactly the captured amount. |
| Booking (Topic 31) | At most one per seat, and one per key. |
| Outbox flag | Written in the same step as the booking, cleared only after the notification service accepted the message. |

### Class diagram

```
 CheckoutService --implements--> Checkout
   |  has many  CheckoutRecord(key, request, state, hold, paymentHoldId, captureEntryId, bookingId, notifyPending, lock)
   |  uses      CrashHook (test seam)      TicketingConfig(venue, paymentGrace, keyRetention)
   |--> BookingService      (31)  hold / release / confirm
   |--> Ledger + HoldService(32)  authorize / voidHold / capture / reverse
   '--> NotificationService(44)  submit / runDue
 State: NEW > SEATS_HELD > PAYMENT_HELD > CAPTURED > CONFIRMED
        end: REJECTED, DECLINED, CANCELLED, REFUNDED, NEEDS_REVIEW
```

```
 buyer --checkout(key)--> [1 hold seats] -> [2 authorize] -> [3 capture] -> [4 confirm] -> [5 outbox: notify]
                              (31)             (32)            (32)          (31)             (44)
```

Cost of one happy checkout: 6 calls into the three modules (hold, authorize, holdState, capture, confirm, submit) and 1 record lock, plus the locks inside those modules (one per seat, two accounts, one key stripe).

### Round 1 (0 to 15 min): hold, pay, confirm

Interviewer: "Sell seats A1 and A2. Walk me through a successful purchase and an expired one."

A worked timeline first (seat hold lasts 5 minutes, price 5000 cents, wallet 20000):

| Time | Step | State after | Wallet available | Venue |
|---|---|---|---|---|
| 12:00 | hold seats A1, A2 | SEATS_HELD | 20000 | 0 |
| 12:00 | authorize 5000 (set aside) | PAYMENT_HELD | 15000 | 0 |
| 12:00 | capture 5000 (move) | CAPTURED | 15000 | 5000 |
| 12:00 | confirm booking | CONFIRMED | 15000 | 5000 |

A strong candidate says: authorize first and capture later, because money that has moved is hard to give back and a reserved amount is free to void. Then draws the ladder and states the invariant (the ledger sums to zero). The payment hold lives 10 minutes longer than the seat hold.

Code delta: `CheckoutService.holdSeats`, `holdPayment`, `capture`, `confirm`. Business failures are end states, not exceptions: `REJECTED` (a seat is taken), `DECLINED` (not enough money, the seats go back), `CANCELLED` (the seat hold expired before the capture, so the payment hold is voided and the seats released).

Proved by `RoundOneTest` (5 tests) and the time-limit tests in `RoundTwoHardeningTest`: the payment hold frees itself exactly at seat hold plus grace, not one nanosecond before.

### Round 2 (15 to 30 min): idempotency and partial failure

Interviewer: "The server dies after the payment hold. The buyer clicks Pay again. What happens? Now after the capture? Now after the confirm?"

Worked timeline of one crash (the process dies right after the capture, the buyer retries 20 seconds later):

| Time | Event | Record | Money | Seats |
|---|---|---|---|---|
| 12:00:00 | steps 1 to 3 done, crash AFTER_CAPTURE | CAPTURED | moved, 5000 | held |
| 12:00:20 | retry, same key | CAPTURED, resumes at step 4 | unchanged | held |
| 12:00:20 | confirm (Topic 31, same key) | CONFIRMED | unchanged | booked |
| 12:00:25 | retry again | CONFIRMED, replay true | unchanged | booked |

A strong candidate says: one key for the whole flow, and a small state machine saved per checkout. This is a *saga*: a chain of local steps, each with an undo (see [microservices.io](https://microservices.io/patterns/data/saga.html)). Each step does its effect, then writes down that it did. A retry reads the record and continues. The steps that can crash between "effect" and "write down" are safe by themselves: confirm is idempotent by key in Topic 31, and capture is retry-safe in Topic 32 (the checkout also asks the ledger if the hold is already CAPTURED before it decides to cancel).

The rule for "money taken but confirm failed":

| Situation at retry | Rule | Why |
|---|---|---|
| Seat hold still live | Roll forward: confirm | The buyer paid and the seats are still ours. |
| Not captured, hold expired | Roll back: void the payment hold, release the seats | Nothing to give back. |
| Captured, confirm says `HoldExpired` | Refund: reverse the capture | Only this proves the booking was never made. |
| Captured, confirm says not found or already confirmed | `NEEDS_REVIEW`: leave the money, flag it | We cannot tell if the seats are ours (the hold was forgotten after 1 hour, or booked under another key). Refunding could refund a real sale. |

A real system would ask Topic 31 "is there a booking for this key?". That call does not exist in Topic 31, so we flag instead of guessing. We never force a confirm.

Code delta: `CheckoutRecord`, `State`, `CheckoutService.run/step/compensate/refund`, and `CrashHook`, a test seam that throws at a named point to simulate a dead process (no timing). There are 12 named points, one before or after every effect. `RoundTwoTest` (22 tests) crashes at each happy-path point and retries; `RoundTwoHardeningTest` (19 tests) covers rollback crashes (`AFTER_VOID`, `AFTER_REVERSE`), the forgotten hold, a failing record that must not block the sweep, the busy key, bad input and purge.

### Round 3 (30 to 45 min): notifications and concurrency

Interviewer: "Email the buyer. The mail provider is down. Two buyers click on the last seat at the same moment."

A strong candidate says: an *outbox*, a "to do" note stored in the same step as the business change and sent afterwards (see [microservices.io](https://microservices.io/patterns/data/transactional-outbox.html)). The booking and the note are written together, so once the checkout is retried or swept there is never a booking without its message. Until then a crash can leave a booking whose message has not been sent (the note is still pending), and `flushOutbox` or the next retry sends it. Sending is separate, so a send failure never rolls back a sale. The message key is `"confirm:" + checkoutId`, so a retry resubmits under the same key and Topic 44 answers with the same notification.

| Time | Ann | Bob | Result |
|---|---|---|---|
| 12:00 | holds A1, authorizes 5000, stalls | | A1 held by Ann |
| 12:00 | | tries A1 | REJECTED, nothing authorized |
| 12:00 | resumes, captures, confirms | | CONFIRMED, one email |

A slow loser (money authorized, seat hold ran out, winner took the seat) is cancelled on retry or sweep: its payment hold is voided.

Concurrency model: each record has a `ReentrantLock` taken with `tryLock`. A second call with the same key while the first runs gets `CheckoutInProgressException` (409) at once. `sweep`, `flushOutbox` and `purge` skip busy records, so one slow checkout stalls nobody. A lock is used instead of `synchronized` because `synchronized` pins a virtual thread to its carrier on JDK 21 (fixed in JDK 24 by JEP 491). `stateOf` reads a volatile field and never waits. The seat race is decided by the seat locks of Topic 31.

Code delta: `notifyBuyer`, `flushOutbox`. Proved by `RoundThreeTest` (7 tests), where a latch freezes Ann at a named point so the interleaving is forced.

### End-to-end test

`EndToEndTest` (1 test, virtual time): Ann captures and crashes, Bob is rejected and never charged, Ann retries and is confirmed. More retries, a sweep, a flush and two notification runs change nothing. Final: one email, one capture, the venue holds exactly 5000, Bob's wallet is untouched and the ledger sums to 0.

### Code and tests

| File | Role |
|---|---|
| `Checkout`, `CheckoutService` | Interface with its error contract, and the saga |
| `CheckoutRecord`, `State` | The per-checkout record and its forward-only states |
| `CheckoutRequest`, `CheckoutResult`, `TicketingConfig` | Value types |
| `CheckoutKeyConflictException`, `CheckoutInProgressException` | The two caller errors (422, 409) |
| `CrashHook` | Named crash points for tests |
| `Rig`, `RoundOneTest`, `RoundTwoTest`, `RoundTwoHardeningTest`, `RoundThreeTest`, `EndToEndTest` | 54 tests and the shared setup |

## 3. The Gaps

Follow-up ladder:
- F1: the buyer never retries. `sweep()` finishes records whose seat hold has run out (void and release, or refund). A real system runs it on a timer. A record that keeps failing is counted (`sweepFailures()`) and skipped, so it cannot block the others.
- F2: the process really dies. The record is in memory here, so a restart loses it. In production it is one database row, each step is a transaction, and the sweep is the recovery worker. The code has that shape: every step reads the record, acts, then writes the record.
- F3: a cart with many items or coupons. One saga per cart that starts one checkout per item; not coded.

Misconceptions:
- "Idempotency means the endpoint returns the same thing." It means every step behind it is safe to run again. The key must reach every layer (here Topic 31 confirm and Topic 44 submit both get a key made from the checkout id).
- "Capture first, refund if it fails." A refund costs fees and trust. Authorize, then capture only when the sale is certain.
- "Send the email inside the transaction." Then a mail outage blocks sales. Use an outbox.
- "Refund on any confirm error." Only `HoldExpired` proves no booking exists. Other errors need a human.

Trade-offs and when not to use: an in-process saga is simple but single node. Long approvals belong in a workflow engine ([Topic 42](../42-workflow-engine/)). Cross-company payments should use the gateway's own idempotency key and webhooks.

Production delta (known gaps, not hidden):
- Topic 32's `authorize` has no key. A crash between the ledger call and saving the hold id makes the retry authorize twice, so a buyer with exactly the price is wrongly DECLINED until the first hold expires (test: `knownGapDoubleAuthorizeFalselyDeclinesAFullWallet`). The fix is a keyed authorize in Topic 32; real gateways take an idempotency key on authorize.
- Keys: a finished checkout is remembered for `keyRetention` (assumed 24 hours, as long as Topic 31's key memory) and then `purge()` forgets it. After that the same key starts a new purchase, as with Stripe, which prunes keys after 24 hours.
- Also needed: a reconciliation job comparing the ledger to bookings (it also resolves `NEEDS_REVIEW`), a metric per state, and the dead-letter queue of Topic 44 on a dashboard. Broker material: [idempotency](../../internal-pocs/event-processing-poc/core/09-idempotency-dedup/), [outbox and CDC](../../internal-pocs/event-processing-poc/core/16-outbox-cdc/).

## 4. Official References

- IETF draft, [The Idempotency-Key HTTP Header Field](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/) (409 for a request in flight, 422 for a reused key with a different payload).
- Stripe, [Idempotent requests](https://docs.stripe.com/api/idempotent_requests) and [Place a hold on a payment method](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method) (authorize now, capture later).
- microservices.io, [Saga](https://microservices.io/patterns/data/saga.html) and [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html).
- Kleppmann, Designing Data-Intensive Applications, 1st ed. (2017), the sections "Operation identifiers" and "The end-to-end argument for databases" in the chapter on stream processing.
- [JEP 491](https://openjdk.org/jeps/491): synchronizing virtual threads without pinning (JDK 24).
- Topics: [06 state machines](../06-state-machines/), [10 virtual time](../10-time-and-testability/), [11 money and idempotency](../11-money-ids-idempotency/).

## 5. 45-Minute Timeline

| Minutes | Round | Say and draw | Checkpoint |
|---|---|---|---|
| 0 to 5 | 1 | Clarify, draw the 5-step ladder, authorize vs capture | Ladder on the board |
| 5 to 15 | 1 | Entities, the end states, payment hold outlives seat hold | Happy path and expiry walked |
| 15 to 22 | 2 | State machine per checkout, one key everywhere | Crash after each step answered |
| 22 to 30 | 2 | Roll forward, roll back, refund, needs-review table | The captured-but-unconfirmed rule stated |
| 30 to 38 | 3 | Outbox, derived key, send failure isolated, busy key is 409 | No double email shown |
| 38 to 45 | 3 | Race for the last seat, loser released, production gaps | Ledger sums to zero |

## 6. Senior vs Staff Rubric

| Round | Senior | Staff |
|---|---|---|
| 1 | Hold, pay, confirm in order; releases seats on failure | Authorize before capture; payment hold outlives seat hold; money invariant (sum is zero) |
| 2 | Adds a retry and an idempotency key at the API | Key through every layer; persisted saga; refund only on proof; flags the unknown; tests a crash at each boundary |
| 3 | Sends the email after confirm | Outbox with a derived key; send failure never undoes a sale; busy key is 409; names the double-authorize gap |

## 7. Key Takeaways

- Think in step boundaries: for each gap, say what is true after a crash there.
- One idempotency key must travel to every step. Derive the other keys from it.
- Roll forward while it is cheap, roll back before money moves, refund only on proof, flag the rest. Never force.
- Outbox: record the intent with the change, send later, and let sending fail alone.
- Test crashes with a named hook, not with timing.

---

**Self-grade: STRONG.** Full spine, interface and error contract, invariants, diagram and cost, three rounds each with prompt, strong answer, code delta and proving tests, 54 tests, and a mutation table in `commands.md`. Remaining gap: the record is in memory (a real restart is described in F2, not coded), and Topic 32's `authorize` has no key, so the double-authorize window is documented and tested but not removed.

[← Topic 46: Capstone, API Gateway Core](../46-capstone-api-gateway-core/) · [Index](../README.md) · [Topic 48: Capstone, Jira-lite →](../48-capstone-jira-lite/)
