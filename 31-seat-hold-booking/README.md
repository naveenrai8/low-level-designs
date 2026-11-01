# Topic 31: Seat-Hold Booking

Two people click the last cinema seat at the same moment. One of them must win, the loser must be told clearly, and a seat must never be sold twice. Payment takes minutes, so we cannot lock the seat in a database while the user types a card number. Instead we *hold* the seat for a short time, then *confirm* it after payment. The same shape covers movie seats, concert seats, flight seats and hotel rooms: an inventory of numbered units inside one *show* (a screening, a flight, a hotel date).

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Timeline](#5-45-minute-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 1. The Problem

### A worked timeline first (hold lasts 5 minutes)

| Time | What happens | Result |
|---|---|---|
| 10:00:00 | Ann holds A1 and A2 | Hold `h-1` is valid until 10:05:00 |
| 10:02:00 | Bob asks for A2 and A3 | Rejected: A2 is held. The error lists A2. Bob holds nothing, so A3 stays free |
| 10:04:59.999999999 | Anyone looks at A1 | Still HELD: the hold is valid while `now < 10:05:00` |
| 10:05:00 | Ann's payment callback arrives and she confirms `h-1` | **Expired at exactly 10:05:00** (half-open). The answer is "410 Gone". The payment is written down as "needs refund" |
| 10:05:00 | Bob asks for A2 and A3 again | Succeeds: expiry is lazy, so A2 is simply treated as free |
| 10:06:00 | Bob pays and confirms with key `k1` | Booking `b-1` is created (201) |
| 10:06:01 | Bob's phone retries the same confirm with `k1` | The same booking comes back (200). No second booking |

### What is really being probed

- Do you see a hold as a lease with an exact end (`<` or `<=`)? Can you hold several seats all-or-nothing without a deadlock? Do you know payment callbacks are retried and arrive late?

### v1 and follow-ups

- **v1:** unified hold-then-confirm. AVAILABLE, then HELD (hold id, expiry), then BOOKED. HELD goes back to AVAILABLE on release or expiry.
- **F1:** hold several seats atomically.
- **F2:** confirm with an idempotency key and a payment reference, including the late callback.
- **F3:** overbooking (airline).

### Clarifying questions, with the assumed answers

| Question | Assumed answer |
|---|---|
| Who owns a hold? | Whoever has the hold id. No users or auth here. |
| Does a hold lock a seat or a whole show? | Seats only. Different seats never wait for each other. |
| What happens to money that arrives after expiry? | We record "needs refund" and fail the confirm (see F2). |
| One process or many? | One process. The distributed version is in section 3. |
| Out of scope? | Prices (would be `long` minor units, [Topic 11](../11-money-ids-idempotency/)), payments themselves, seat maps, waiting lists, accounts, persistence. |

---

## 2. How It Solves It

### Entities and invariants

- `SeatCell`: one unit with its own lock, a state, the owning hold id and the expiry instant. `Hold`: id, show, sorted seats, `expiresAt`. `Booking`: id, hold, seats, payment reference. `IdempotencyStore`: key to (request fingerprint, booking, expiry).
- A seat has at most one owner. A BOOKED seat never goes back. A hold's seats are all owned by that hold or all released: never part. One idempotency key means one booking. A hold is valid while `now < expiresAt`.

### The interface and its error contract

```java
void addShow(String showId, Set<String> seatIds);
Hold hold(String showId, List<String> seatIds);                              // SeatUnavailableException (lists ALL blocking seats, holds nothing), UnknownSeatException, ShowNotFoundException, IllegalArgumentException (empty/duplicate list)
void release(String holdId);                                                 // repeatable; HoldNotFoundException; AlreadyConfirmedException
ConfirmResult confirm(String holdId, String idempotencyKey, String paymentRef); // HoldExpiredException (+ refund note), HoldNotFoundException, AlreadyConfirmedException, IdempotencyConflictException
SeatState seatState(String showId, String seatId);  List<String> availableSeats(String showId);
int sweep();                                                                 // memory tidy-up only; never changes an answer
List<RefundNeeded> refundsNeeded();  void refundDone(String idempotencyKey);
```

The errors are unchecked `BookingException`s, so a caller (the controller) decides which ones to translate. `IllegalArgumentException` means "your input is wrong", `SeatUnavailableException` means "you lost a race". They are different on purpose.

### Class diagram
```
BookingService (interface) <|-- SeatHoldBookingService
SeatHoldBookingService --> Clock, BookingConfig(holdTtl, idempotencyTtl, retention), Supplier<String> holdIds/bookingIds, OverbookPolicy
SeatHoldBookingService o-- Show --o SeatCell (lock, state, holdId, expiresAt)
SeatHoldBookingService o-- HoldRecord(Hold, status HELD|CONFIRMED|RELEASED)
SeatHoldBookingService o-- IdempotencyStore(key -> fingerprint, Booking, expiresAt)
SeatHoldBookingService o-- RefundNeeded notes
```
### Sequence: hold, pay, confirm (and the late callback)

```
Client            Service                      Payment gateway
  | hold(A1,A2)      |                                |
  |----------------->| lock A1,A2 (sorted); check free; mark HELD until now+5m
  |<-- Hold h-1, expiresAt 10:05                       |
  | pay ------------------------------------------->  |
  |                  |            callback(h-1, pay-1, key k1)
  |<-----------------|<------------------------------- |
  | confirm(h-1,k1,pay-1) -> key known? replay it.
  |                  | else lock seats; now < expiresAt ?
  |                  |   yes: mark BOOKED, store (k1 -> booking)   => 201 Booking b-1
  |                  |   no : write RefundNeeded(k1, pay-1)        => 410 Gone
```

### The algorithm and its cost

A seat is *free* if it is AVAILABLE, or HELD with `now >= expiresAt`. That one test is the whole expiry rule, and it is one method, `SeatCell.isFree`, used by hold, `seatState` and sweep, so they cannot disagree. The same idea for holds is `Hold.isLiveAt(now)`.

| Step of `hold(A2, A1)` | What happens |
|---|---|
| 1 | Check the show, the list (not empty, no duplicates) and that every seat exists |
| 2 | Sort the seat ids: A1, A2 |
| 3 | Lock A1, then A2 |
| 4 | Read the clock once. Collect every seat that is not free |
| 5 | If any: throw `SeatUnavailableException` with all of them. Nothing was changed |
| 6 | Else mark all HELD with `expiresAt = now + ttl`, store the hold record, unlock |

Cost: O(k log k) for k seats (the sort), plus k lock operations. `confirm` is O(k) and the idempotency lookup is O(1) amortised.

**Lazy expiry plus an optional sweep.** Nothing runs on a timer to free seats. An expired hold is just a seat that passes the free test. `sweep()` only resets old cells and forgets old records to save memory. `SweepDifferentialTest` runs 20 random sequences of 300 operations on two services, one sweeping every third step, and requires identical answers as text. To make that true, a hold id is "forgotten" (404) at `expiresAt + retention` by a time rule that `findHold` applies itself, not by whether the sweep ran.

**Half-open expiry** is exactly the bug class of [Topic 10](../10-time-and-testability/) (`InclusiveExpiryHoldRegistry`, where `<=` gave one extra tick). Tests check one nanosecond before the tick (HELD, confirm works) and the tick itself (free, confirm fails).

### Concurrency model

- Shared state: each seat's fields, the hold records, the idempotency store.
- Primitive: **one `ReentrantLock` per seat, taken in sorted seat-id order**. That is the global lock order of [Topic 08](../08-multi-object-atomicity/): if everyone takes locks in the same order, nobody can wait in a circle.
- Why per seat and not one lock per show: a big concert has thousands of seats and users want different ones. One show lock is simpler and cannot deadlock, but every hold in the show queues behind it. Per-seat locks cost a sort and keep unrelated buyers parallel. For a small show the show lock is fine: say that out loud.
- `lockInterruptibly` is used so an interrupt can get a thread out of a stuck wait. Locks are released in reverse order in `finally`.
- Release and confirm lock the same seats of the hold, so they exclude each other: exactly one wins (tested 50 rounds). `HoldRecord.status` is read and written only under those locks.
- **A key lock comes before the seat locks.** Confirm first takes a lock chosen by the idempotency key (64 stripes), checks the key, then takes the seat locks. So two confirms with the same key run one after the other even if they name different holds: the second finds the first's outcome and gets a conflict. Without it, both passed the check and both booked (the bug the reviewer found; `oneKeyCannotBookTwoHolds` pauses thread 1 right after its check with a barrier to prove it). Lock order is always key lock, then seat locks sorted, so no cycle. Locks and CAS in general: [Java concurrency guide](../../internal-pocs/Java-poc/concurrency/).
- **Confirm also checks it still owns every seat.** If the clock steps backwards, an old expired hold looks live again while a newer hold owns the seat. Confirm requires each seat to be HELD by this very hold id, else it is treated as lost (`backwardClockStepCannotSellASeatTwice`).

### F2 in detail: idempotent confirm

*Idempotent* means doing it twice has the same effect as doing it once. The caller sends a key. We remember (key, fingerprint of the request, booking).

| Call | Result |
|---|---|
| `confirm(h-1, k1, pay-1)` first time | Booking `b-1`, `replay=false` (HTTP 201) |
| same again | the same `b-1`, `replay=true` (HTTP 200). The id supplier is not called again |
| `confirm(h-1, k1, pay-2)` | `IdempotencyConflictException` (HTTP 422): same key, different request |
| `confirm(h-1, k2, pay-1)` after booking | `AlreadyConfirmedException` (HTTP 409): the hold is done, a new key cannot book it again |
| `confirm` at or after expiry | `HoldExpiredException` (410) and a `RefundNeeded` note. The expired outcome is stored under the key too |
| same expired request again | the same 410, and no second note, even after `refundDone` (the note stays as a tombstone) |
| same key, another `paymentRef`, after expiry | `IdempotencyConflictException`: that payment would otherwise never be refunded |

**The late callback decision.** The money has already moved when the gateway calls us, so we cannot just say no and forget. Options: (a) book anyway if the seat is still free, (b) fail and refund. We chose (b) with an explicit note, because (a) sells a seat the user may have already seen as lost, and it makes the result depend on whether someone else grabbed it in between. The note is written once per key and a refund worker drains it with `refundsNeeded()` and `refundDone(key)`. Only a confirm that finds the hold expired (or lost its seats) writes a note. Failures for a released or already-booked hold write none: refunding those is the payment layer's job, so it is out of scope here. In production the note is an outbox row (section 3).

**Memory bound.** Entries live `idempotencyTtl` (24 h by default) and are dropped when `now >= expiresAt`. All entries live equally long, so insertion order is normally expiry order and cleaning is "pop from the front while expired", amortised O(1), run on every confirm (and on sweep), so the store is bounded even if sweep never runs. Lookup never trusts that order: it checks the entry's own expiry, because a clock step can break it. A key older than its TTL is no longer a replay. That is a stated trade-off: the TTL must be longer than the longest time a client may keep retrying.

### F3: overbooking, coded small

`OverbookPolicy.sellable(capacity)` says how many units may be sold. `addShow` adds `sellable - capacity` extra cells named `OVERBOOK-1`, `OVERBOOK-2`, ... so the whole hold/confirm machinery needs no change. Test: capacity 3 with allowance 2 allows exactly 5 holds and refuses the 6th. Design delta not coded: a real airline needs fare classes, a cancellation-rate forecast, and a *denied boarding* compensation workflow when everyone shows up.

### Code and tests

| File | What it holds |
|---|---|
| `BookingService` | the interface and the full error contract |
| `SeatHoldBookingService`, `SeatCell`, `IdempotencyStore`, `Hold`, `Booking`, `ConfirmResult`, `RefundNeeded`, `BookingConfig`, `OverbookPolicy`, `SeatState`, `*Exception` | the algorithm, per-seat and per-key locks, the key store, values, settings, F3 seam, errors |
| `Gap`, `LockOrder` | test hooks (`LockOrder.REQUEST_ORDER` exists only to show the deadlock) |
| `HoldLifecycleTest` (9), `IdempotencyStoreTest` (1), `OverbookPolicyTest` (4), `SweepDifferentialTest` (1) | timeline and exact tick, store expiry, F3, sweep changes nothing and really frees |
| `MultiSeatHoldTest` (6) | no partial holds, all blocking seats listed, forced deadlock, first lock is the smallest id, locks released if a hook throws, hammer |
| `IdempotentConfirmTest` (16) | replay, conflict, fingerprint, TTL, refund tombstone, one key two holds, backwards clock, confirm versus release |
| `app/.../booking/BookingController`, `BookingConfig`, `BookingProperties` | REST adapter, Clock bean `bookingClock`, `lld.booking.*` settings, sweep timer |
| `app/.../booking/BookingControllerTest` (13) | every status code with a movable clock |

The tests use no sleep: the deadlock test uses a barrier after the first lock, the fix test records the first seat each thread locked (both must be `A1`), and the same-key test pauses one thread at `Gap.Phase.AFTER_KEY_LOOKUP`.

### The HTTP adapter

| Request | Success | Errors |
|---|---|---|
| `POST /api/booking/shows/{showId}/holds` body `{"seatIds":[...]}` | 201 + hold (id, expiresAt) | 409 ProblemDetail with `unavailableSeats`; 404 unknown show; 400 unknown seat or bad list |
| `POST /api/booking/holds/{holdId}/confirm`, header `Idempotency-Key` | 201 first time, 200 same body on replay | 400 no key; 422 same key different body; 409 already booked; 410 expired; 404 unknown hold |
| `DELETE /api/booking/holds/{holdId}` | 204 (also if repeated) | 404 unknown; 409 already booked |

Each controller method wraps only the core call in `try/catch` and maps the exception right there. There is deliberately no global `@ExceptionHandler(IllegalArgumentException.class)`. The tests move a `MovableClock` registered as a `@Primary` bean over the `bookingClock` bean. See [SpringBoot-poc 33a](../../pocs/SpringBoot-poc/33a-problemdetail-rfc9457-errors).

---

## 3. The Gaps

### The follow-up ladder

| Step | Design delta |
|---|---|
| F1 multi-seat | Sorted lock order (or one show lock). All-or-nothing check under the locks. Report every blocking seat. |
| F2 idempotent confirm | Key + request fingerprint + stored answer with a TTL. Check again under the locks. Late payment becomes a refund note. |
| F3 overbooking | A policy that says how many to sell. Extra cells, no change to the machinery. |

### Misconceptions

| I thought | Actually |
|---|---|
| A timer must free expired holds | No. Lazy expiry on access is enough, and a sweep only saves memory. |
| `now <= expiresAt` is fine | That gives a hold one extra tick. Expired AT `expiresAt` (Topic 10). |
| Check all seats, then hold them, without locks | Two callers both see them free. Check and act must be under the locks. |
| Same key means same booking, so skip the request check | Same key with a different body is a client bug, return 422, not the old answer. |
| Late payment: just book the seat | Money and seat are now decoupled. Record a refund and fail. |
| Checking the key then locking the seats is enough | Two holds with one key both pass the check. The check and the claim need a lock by key. |
| Time never goes backwards | A wall clock can step back, so an expired hold looks live. Confirm re-checks seat ownership. |
### Trade-offs and when not to use

- Holds make unpaid seats unavailable: a short TTL helps but annoys slow payers. In memory means a restart loses everything, fine only for a teaching model.

### Production and distributed delta

- **Database rows.** The seat table has `status`, `hold_id`, `expires_at`. Hold with a conditional update, no lock held while the user pays: `UPDATE seat SET status='HELD', hold_id=?, expires_at=? WHERE show_id=? AND seat_id IN (...) AND (status='AVAILABLE' OR expires_at <= now())` and check the row count equals N, else roll back. Or `SELECT ... FOR UPDATE` in sorted order inside a short transaction (same ordering rule as Topic 08). For "give me any free seat" use `FOR UPDATE SKIP LOCKED` so concurrent buyers skip rows instead of waiting. Guides: [Postgres locking](../../pocs/Postgres-poc/10_transactions/), [SKIP LOCKED queue](../../pocs/Postgres-poc/24_job_queue/), [flight booking schema](../../pocs/Postgres-poc/41_flight_booking_schema_design/).
- **Redis holds.** `SET seat:{show}:{id} {holdId} NX PX 300000` takes a seat and expires it by itself. Multi-seat needs a Lua script (all or nothing), and a confirm must check the value is still your hold id. Redis expiry is not tied to a database commit, so the source of truth for BOOKED stays in the database. See [Redis distributed locking](../../pocs/Redis-poc/11-patterns/03-distributed-locking/) and [idempotency](../../pocs/Redis-poc/11-patterns/06-idempotency/).
- **Payment events.** Write the booking and an outbox row (`BookingConfirmed`, `RefundNeeded`) in one transaction; a relay publishes them. See [outbox and CDC](../../internal-pocs/event-processing-poc/core/16-outbox-cdc/) and [idempotency and dedup](../../internal-pocs/event-processing-poc/core/09-idempotency-dedup/). Store idempotency keys in a table with a unique index, not in memory.
- **Clock.** In a cluster, take `now()` from one source (the database) or the half-open rule can differ per node.

---

## 4. Official References

- [The Idempotency-Key HTTP Header Field (IETF draft)](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/): §2.7: 400 for a missing key, 422 for a reused key with a different request, 409 for a retry while the first is still running.
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457): the 409 body and its extension members (`unavailableSeats`).
- [RFC 9110: HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html): 409 Conflict, 410 Gone, 201 Created, 204 No Content.
- [PostgreSQL: SELECT, the locking clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE): `FOR UPDATE`, `NOWAIT`, `SKIP LOCKED`.
- [PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html): row locks and deadlocks.
- [Redis SET](https://redis.io/docs/latest/commands/set/): `NX` and `PX`.

---

## 5. 45-Minute Timeline

| Minutes | What to do |
|---|---|
| 0 to 5 | Questions in section 1. Say why a hold exists: payment is slow. |
| 5 to 12 | Draw the three states and walk the 10:00 timeline. Say `now < expiresAt`. |
| 12 to 22 | Write the interface and errors. Code `hold` with one lock and an injected clock. |
| 22 to 30 | F1: why per-seat locks need an order, sort, all-or-nothing, the deadlock test. |
| 30 to 38 | F2: key, fingerprint, replay, late callback decision, TTL. |
| 38 to 45 | F3 in two minutes, then the SQL conditional update and Redis `SET NX PX`. |

## 6. Senior vs Staff Rubric

| | Senior | Staff |
|---|---|---|
| Expiry | A timer frees seats | Lazy expiry, exact half-open tick, sweep proven not to change answers |
| Multi-seat | Loops and rolls back on failure | Sorted lock order, forced-interleaving test, no partial state |
| Confirm | Marks BOOKED | Idempotent by key + request, 422 on a changed request, bounded key store |
| Late payment | Not mentioned | An explicit choice with a refund note and a reason |
| HTTP | 200 or 500 | 201/200/409/410/422/404/204, ProblemDetail, errors mapped at the call site |
| Scale | "Use a database" | Conditional update or SKIP LOCKED, Redis NX PX, outbox, single time source |

## 7. Key Takeaways

- A hold is a lease: valid while `now < expiresAt`. Expire lazily, and let a sweep only save memory.
- Several seats at once: lock in one global order and check-then-act under the locks. Report every blocking seat.
- Confirm must be idempotent. Same key and request gives the same booking, same key and different request gives 409, and a late payment is recorded for refund, not forgotten.

[← Topic 30: Sliding-Window Metrics](../30-sliding-window-metrics/) · [Index](../README.md) · [Topic 32: Double-Entry Ledger →](../32-double-entry-ledger/)

**Self-grade: STRONG.** Measured on one machine (JDK 21.0.8): 37 core tests plus 13 REST tests, green three runs in a row, and 13 hand-made mutations each fail at least one test. Remaining gap: the SQL and Redis versions are described, not built, and holds cannot be extended or listed per user.
