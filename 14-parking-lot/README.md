# Topic 14: Parking Lot

"Design a parking lot" looks like a drawing exercise: levels, spots, vehicles. The interviewer is really waiting for four things:

1. Two gates see the last spot as free at the same moment, and both hand it out.
2. The fee is wrong at the edges (exactly 1 hour, 1 hour and 1 second) or uses a `double`.
3. A car parks twice, or a ticket is used twice to leave.
4. Prices and reservations are hard-coded, so each change touches the core.

## Table of Contents

0. [Words used in this topic](#0-words-used-in-this-topic)
1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Spot** | One place to park. It has a size: SMALL, MEDIUM or LARGE. |
| **Ticket** | Proof that a vehicle is parked. It records the entry time and the spot. |
| **Gate** | An entrance or exit. Many gates run at once, so each is a thread. |
| **Minor units** | The smallest coin: cents. A fee of 250 means 2.50. We use `long`, never `double`. |
| **Strategy** | A swappable rule behind an interface. Here: how a stay is priced. |
| **Compare-and-set (CAS)** | "Change this value to B only if it is still A." One atomic step. If another thread changed it first, it fails and you try something else. |
| **Hold / reservation** | A spot kept for one vehicle for a limited time. |
| **TTL** | Time to live: how long a hold lasts. |
| **Half-open** | A time range that includes its start and excludes its end: `start <= now < end`. |

---

## 1. The Problem

**What is really being tested:** modelling (what is an entity, what is a rule), a correct fee, and one real concurrency decision: who gets the last spot.

**v1 requirements**
- A lot has several levels. Each level has SMALL, MEDIUM and LARGE spots.
- `park(vehicle)` returns a ticket. `unpark(ticketId)` returns a receipt with the fee.
- The fee depends on the time between entry and exit. Time comes from an injected `Clock`.

**Clarifying questions, with the answers we assume**

| Question | Assumed answer |
|---|---|
| Which vehicle fits which spot? | Motorcycle: any. Car: MEDIUM or LARGE. Truck: LARGE. |
| Which spot do we pick? | The smallest that fits, lowest level first. Cheap to explain and keeps big spots for big vehicles. |
| How do we round time? | Round **up** to the next started hour. 0 minutes costs 0. |
| What is money? | `long` minor units. |
| Can a parked plate park again? | No: `DuplicateVehicle`. |
| Does a ticket work twice? | No: `TicketAlreadyUsed`. A ticket we never issued gives `UnknownTicket`. |

**Out of scope:** payment, licence plate recognition, a display board, multiple lots, a database.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Rule that must always hold |
|---|---|
| Spot | Holds at most one of: free, a reservation, a ticket. |
| Plate | At most one live ticket or reservation per plate. |
| Ticket | Used to leave once only. |
| Reservation | Valid while `now < madeAt + ttl`. Expired at exactly `madeAt + ttl`. |

### The interface (error contract first)

`ParkingLot` has `park`, `unpark`, `reserve`, `parkReserved`, `freeSpots`. Every failure is a `ParkingException` subtype: `LotFull`, `DuplicateVehicle`, `UnknownTicket`, `TicketAlreadyUsed`, `UnknownReservation`, `ReservationExpired`. When one is thrown, spots and tickets are as they were. The one tidy-up: `parkReserved` on a hold that has run out releases that hold's spot and plate, then throws `ReservationExpired`. `PricingStrategy.fee(type, entry, exit)` returns a `long` and throws `IllegalArgumentException` if exit is before entry.

```
            +-----------------+        +------------------+
 gates ---> |  ParkingLot     |------->| PricingStrategy  |<-- Hourly / Flat / Weekend
            | (InMemory...)   |        +------------------+
            +--------+--------+
                     | uses            Clock (injected)
                     v
        Spot[ AtomicReference: null | Reservation | ticketId ]
```

### The algorithm, with a worked example first

One MEDIUM spot is left. Gate A and Gate B each have a car.

| Step | Gate A | Gate B | Spot state |
|---|---|---|---|
| 1 | looks: free | looks: free | `null` |
| 2 | CAS(`null` to `T1`) succeeds | | `T1` |
| 3 | | CAS(`null` to `T2`) fails, the spot is no longer `null` | `T1` |
| 4 | gets ticket T1 | moves on to the next spot, finds none, `LotFull` | `T1` |

Without CAS (check "free?", then set), step 3 would succeed too and the spot would be given twice. That is the bug the test below forces.

Search order: for each size from smallest that fits, scan the spots in level order, and try CAS on the first free one. Cost: O(number of spots) per park in the worst case. Fine for a lot of thousands. A per-size free queue makes it O(1) (see production delta).

**Fee, worked first**

| Stay | Hours charged | Fee at 200 per hour |
|---|---|---|
| 0 min | 0 | 0 |
| 1 min | 1 | 200 |
| exactly 60 min | 1 | 200 |
| 60 min and 1 second | 2 | 400 |

Formula: `hours = ceil(stayNanos / 1 hour)`, `fee = hours * rate`, using `Math.ceilDiv` and `Math.multiplyExact` (overflow throws instead of wrapping).

### The concurrency model

- **Shared state:** the spot. **Invariant:** one owner per spot. **Primitive:** one `AtomicReference` per spot with CAS. **Why:** gates only collide when they want the same spot, so there is no global lock and no lock is held while searching. For CAS basics see [Java-poc 19](../../internal-pocs/Java-poc/concurrency/19-atomics-and-cas/). For why a lock-based version needs ordering, see Topic 08.
- **Plate claim:** `putIfAbsent(plate, owner)` before the spot search. Two gates with the same plate: one wins, the other gets `DuplicateVehicle`. If the spot search fails, the plate claim is rolled back.
- **Ticket use:** `usedTickets.add(id)` returns true for one caller only. If pricing throws, the id is removed again so the driver can retry.
- **Free spot count:** `freeSpots` is a snapshot; by the time you read it the answer may be stale. Never use it to decide whether to park. Call `park` and handle `LotFull`.

### Code and tests

| File | What it is |
|---|---|
| [ParkingLot](src/main/java/com/lld/parkinglot/ParkingLot.java) | Interface and error contract |
| [InMemoryParkingLot](src/main/java/com/lld/parkinglot/InMemoryParkingLot.java) | CAS-based implementation |
| [HourlyPricing](src/main/java/com/lld/parkinglot/HourlyPricing.java), [FlatPricing](src/main/java/com/lld/parkinglot/FlatPricing.java), [WeekendPricing](src/main/java/com/lld/parkinglot/WeekendPricing.java) | F1 strategies |
| [ClaimHook](src/main/java/com/lld/parkinglot/ClaimHook.java) | Test seam between "saw it free" and "took it" |
| [ParkingLotTest](src/test/java/com/lld/parkinglot/ParkingLotTest.java) | 15 behaviour tests: placement, fee boundaries (including 60 min + 1 ns), double park, bad tickets, hold boundary, weekend pricing in Asia/Tokyo |
| [ConcurrencyTest](src/test/java/com/lld/parkinglot/ConcurrencyTest.java) | 7 race tests. Four force an exact interleaving with a barrier or hook (last-spot race, loser falls through, reserve vs park, the expiry-instant `parkReserved` race). Three are start-gate latch tests with exact totals (same plate, 64 gates, one ticket) because the interleaving there happens inside the JDK maps |

---

## 3. The Gaps

### F1: Pricing rules as a Strategy
`PricingStrategy` is one method. `HourlyPricing` rounds up per hour, `FlatPricing` charges per visit, `WeekendPricing` picks between two strategies by the **entry** day in a given zone. The zone is a constructor argument because the same instant is Saturday in one city and Friday in another. A stay starting Friday 23:00 is a weekday stay: simple and predictable. Splitting at midnight would be the extension. The lot never changes when a price rule is added.

### F2: Concurrent entry gates, no double-assign
Done with CAS (section 2). The proof is a barrier inside `ClaimHook`: both gates stop after seeing the spot free, then are released together. Result: exactly one ticket and one `LotFull`. A second test shows the loser falls through to the next spot. No sleeps. Money lesson from Topic 21 applies: test with exact totals (64 gates, 10 spots: exactly 10 tickets, 10 distinct spots).

### F3: Reservations with hold expiry (implemented, small)
A reservation is stored in the spot's own state, so a held spot is never given away while the hold is valid. Same idea as Topic 10's holds, but **lazy**: nothing runs on a timer. The hold is half-open: valid for `now < expiresAt`. A hold made at t with TTL d is valid at `t + d - 1ns` and expired at exactly `t + d`. Both instants are tested. After expiry the spot counts as free, and the next gate that CASes over the old `Reservation` takes it. Every path uses CAS against what it saw, including `parkReserved` (reservation to ticket) and `unpark` (ticket to free). The race at the boundary: gate A checks at `expiresAt - 1ns` and passes, gate B at `expiresAt` takes the spot, then A's CAS fails and A gets `ReservationExpired`. A plain `set` there would overwrite B and make two tickets for one spot; a test with a hook between A's check and A's take proves it (it fails with a plain `set`). Lazy expiry means no scheduler thread to test, and the clock decides.

### Trade-off in `unpark`

The fee is computed before the ticket is marked used. If pricing throws (a backwards clock), the ticket is untouched and still works. If two gates unpark at once, both compute a fee but only one wins the single `usedTickets.add`, so "already used" is never said about a ticket that is still valid. The cost is a wasted fee calculation on the loser.

### Misconceptions

| Myth | Reality |
|---|---|
| "Check `isFree()` then `occupy()` is fine if both methods are synchronized" | Each call is safe alone, the pair is not. Two gates both pass the check. The decision and the take must be one step. |
| "Fee = hours as a double" | Floating point drifts and rounds the wrong way at the edges. Use whole nanoseconds, `ceilDiv`, and `long`. |
| "Unpark twice is harmless" | It would free a spot that may now belong to another car. The ticket is single-use. |
| "Expiry needs a background thread" | Not when time is injected: compare `now` with `expiresAt` when it matters. |
| "`freeSpots() > 0` then `park()`" | Stale by the time you call. Just call `park`. |

### Trade-offs and when not to

- Linear scan is simple. For huge lots, keep a free queue per size.
- CAS on one spot is great when contention is spread. If every gate always wants the same spot (a fixed assignment), a lock is just as good.
- A lazy hold means `freeSpots` and `park` must both treat an expired hold as free. The clock only says "looks free" (`isFreeAt`); the final answer is always the spot's compare-and-set, so a gate whose clock reading is a nanosecond early still loses cleanly (see F3).

### Production and distributed delta

- `usedTickets`, `usedReservations` and expired `reservations` only ever grow here. Production needs a retention window or a database table with a cleanup job.
- Persist spot state in a database with a unique constraint or a conditional update (`UPDATE ... WHERE state IS NULL`), which is the same CAS in SQL. See Topic 11 for idempotency keys on entry requests.
- Gates are separate machines: the ticket id should come from an idempotent request key so a retried scan does not park twice.
- Unpark with a lost-ticket flow, and a clock-skew policy between gates, are real requirements we left out.

---

## 4. Official References

- [`java.time.Clock`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html): why time is injected.
- [`AtomicReference.compareAndSet`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html): the CAS used per spot.
- [`Math.ceilDiv` / `multiplyExact`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html): rounding up and overflow-safe money.
- Same topic elsewhere: [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/), Topics 06, 08, 10, 11.

---

## 5. 45-Minute Interview Timeline

| Minutes | Do |
|---|---|
| 0 to 5 | Clarify: vehicle to spot fit, rounding, one ticket per plate. State out of scope. |
| 5 to 12 | Entities, the `ParkingLot` interface and the error contract. |
| 12 to 22 | Code `park` and `unpark` with the spot rule and the fee (ceil, `long`). |
| 22 to 30 | Raise the race for the last spot. Fix with CAS. Say how you would test it with a barrier. |
| 30 to 37 | `PricingStrategy` (F1) and reservations with half-open expiry (F3). |
| 37 to 45 | Production: database conditional update, idempotent gates, free queue. |

If you are behind at minute 25, describe pricing and reservations in words. Correct concurrency beats more features.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Model | Spot, Vehicle, Ticket, Level classes | Few entities, one rule per invariant, errors as a contract |
| Fee | Computes hours times rate | States rounding, tests the exact boundaries, `long`, injected clock |
| Concurrency | `synchronized` on park | Names check-then-act, uses CAS per spot, proves it with a forced interleaving |
| Pricing | `if/else` on vehicle type | Strategy, with the weekend zone and entry-day rule stated |
| Reservations | Spot status RESERVED plus a timer | Lazy half-open expiry, boundary instant tested |
| Failure | Returns null | Typed errors, rollback of the plate claim, ticket given back if pricing fails |

## 7. Key Takeaways

1. The one real decision is "who gets this spot". Make the check and the take a single CAS.
2. Prove a race with a barrier inside a hook, not a sleep.
3. Money is `long`. Round up, and write the rule down. Test 0, 1, exactly-an-hour, and an hour plus one.
4. A plate, a ticket and a reservation are each single-use. Enforce it with an atomic add or `putIfAbsent`.
5. Half-open time ranges (`start <= now < end`) remove the "is the boundary in or out" argument.
6. With an injected clock, expiry can be lazy and needs no thread.
7. Never trust `freeSpots()` to decide; try and handle `LotFull`.

---

**Self-grade: STRONG.** It has the full spine, a worked example before each algorithm, a deterministic race proof, fee and hold boundaries tested, and F1 to F3 all coded. Known gap: the database-backed version and the free-queue index are described, not coded, and no timing numbers were measured.

[← Topic 13: LLD to HLD Seams](../13-lld-to-hld-seams/) · [Index](../README.md) · [Topic 15: Vending Machine →](../15-vending-machine/)
