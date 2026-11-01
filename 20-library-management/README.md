# Topic 20: Library Management

"Design a library" sounds like a catalogue exercise: books, members, a `borrow` method. The interviewer is really waiting for four things:

1. Two members take the last copy at the same moment, and both leave with it.
2. A failure halfway through leaves a copy marked loaned with no loan record (or the reverse), or a member over their limit.
3. The fine is wrong at the edges (returned exactly on the due time, one second late) or uses a `double`.
4. A returned copy ignores the people waiting for it, or a broken email server makes the return fail.

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
| **Title** | A book as the catalogue knows it (one ISBN). Many copies can share it. |
| **Copy** | One physical book. It is the thing that gets lent. |
| **Loan** | One member borrowing one copy, with a due instant. |
| **Hold** | A place in the queue for a title that has no free copy. |
| **Pickup window** | How long a copy waits on the hold shelf for the member it was kept for. |
| **Compare-and-set (CAS)** | "Change this value to B only if it is still A." One atomic step. If another thread changed it first, it fails. See [Java-poc 19](../../internal-pocs/Java-poc/concurrency/19-atomics-and-cas/). |
| **Half-open** | A range that includes its start and excludes its end. Here: late is the open range strictly after due, and on time is `returned <= due`. |
| **Port** | An interface the core calls to reach the outside world (here: the notifier). |
| **Minor units** | The smallest coin: cents. A fine of 250 means 2.50. Always `long` (see [Topic 11](../11-money-ids-idempotency/)). |

---

## 1. The Problem

**What is really being tested:** one real concurrency decision (who gets the last copy), changes that touch three records and must not half-happen, a correct fine, and a fair queue.

**v1 requirements**
- A catalogue of titles. Each title has copies at branches.
- `checkout`, `returnCopy` with a fine from an injected `Clock`.
- A per-member loan limit.

**Clarifying questions, with the answers we assume**

| Question | Assumed answer |
|---|---|
| Loan limit? | 3 open loans per member (a policy value). |
| Loan length? | 14 local days, at the same time of day in the library's `ZoneId`. |
| When is a return late? | Strictly after the due instant. At exactly the due instant it is on time. |
| How many fine days? | Local 24-hour periods after the due instant, in the library's zone, rounded up. One second late is 1 day. |
| Fine amount? | `days * 25`, capped at 1000 per loan. `long` minor units. |
| Same title twice? | A member cannot have the same title on loan twice. |
| Hold pickup window? | 2 days from the moment the copy is kept for them. |
| Can I hold a title I have on loan? | No. Nor twice. Nor when a copy is on the shelf (borrow it). |

**Out of scope:** payment of fines, renewals, reservations by date, e-books, a database, auth.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Rule that must always hold |
|---|---|
| Copy | In exactly one state: AVAILABLE, LOANED, ON_HOLD or IN_TRANSIT. |
| Loan | Open at most once. A closed loan can never be closed again. |
| Member | `loans` equals their open loans and never exceeds the limit. A title appears once in their borrowed set. |
| Hold queue | FIFO per title. A title with a waiting member never has a copy sitting on a shelf. |

### The interface (error contract first)

`Library` has `addTitle`, `addCopy`, `registerMember`, `search`, `checkout`, `returnCopy`, `placeHold`, `transfer`, `receive`, `sweepHolds`, plus read-only snapshots. Every failure is a `LibraryException` subtype (`NoCopyAvailable`, `LoanLimitReached`, `AlreadyBorrowed`, `AlreadyReturned`, `DuplicateHold`, `CopyAvailable`, `CopyNotTransferable`, `NotInTransit`, and the `Unknown...` ones). A failed call changes nothing, except that it may pass on holds that had already expired. Notifications go out through the `HoldNotifier` port. Formal UML is skipped; the diagram below is the whiteboard version.

```
 members --> Library (InMemoryLibrary) --> HoldNotifier (port)  email / SMS / queue
                |   uses  Clock (injected), LibraryPolicy
                v
   Copy: AtomicReference< CopyState{status, branch, member, until} >
   Member: borrowed Set + loans counter     Queue per title: FIFO + lock
   open loans map / issued loans map
```

### The algorithm, with a worked example first

**Last copy.** One copy of DUNE is left. Ann and Bob both press "borrow".

| Step | Ann | Bob | Copy state |
|---|---|---|---|
| 1 | looks: AVAILABLE | looks: AVAILABLE | `AVAILABLE` |
| 2 | CAS(`AVAILABLE` to `LOANED(ann)`) succeeds | | `LOANED(ann)` |
| 3 | | CAS fails, the state is no longer what Bob saw | `LOANED(ann)` |
| 4 | gets the loan | no other copy: `NoCopyAvailable` | `LOANED(ann)` |

Without CAS (check "available?", then set) step 3 would succeed too and one copy would have two borrowers. The test forces exactly that gap with a barrier in a hook, and the mutation check below shows it catches the bug.

**Checkout touches three things**, so it runs in a fixed order and undoes in reverse:

| Step | Action | If a later step fails |
|---|---|---|
| 1 | Reserve the member's slot: add the title to their set, CAS the count up (never above the limit). | not needed |
| 2 | Claim a copy with one CAS. | give the slot back |
| 3 | Write the loan record. | CAS the copy back, then give the slot back |

A failure can never leave a loaned copy with no loan, because the only code between step 2 and step 3 is the clock read and an id; and if that throws, the copy is restored. The test `failureAfterClaimLeavesNothingBehind` throws from the hook right after the claim and checks everything is back.

**Return and the hold queue.** Ann returns DUNE. Bob and Cat are waiting.

| Time | What happens | Result |
|---|---|---|
| day 0 | Return arrives. Queue is `[bob, cat]` | copy becomes `ON_HOLD(bob, until day 2)`, queue `[cat]`, Bob notified |
| day 1 | Cat tries to borrow | `NoCopyAvailable` (kept for Bob) |
| day 2, exactly | Bob's window ends (half-open: `now < until` to collect) | copy becomes `ON_HOLD(cat, until day 4)`, Cat notified |
| day 3 | Cat collects | `LOANED(cat)` |

Nobody runs a timer. A checkout, hold or `sweepHolds` call notices expired holds through the `Clock` and passes them on.

**Fine, worked first.** Due is day 14 at 10:00 UTC.

| Returned at | Days late | Fine (25 per day, cap 1000) |
|---|---|---|
| exactly day 14, 10:00:00 | 0 | 0 |
| 1 ns later | 1 | 25 |
| exactly day 15, 10:00:00 | 1 | 25 |
| day 15, 10:00:00 plus 1 ns | 2 | 50 |
| 100 000 days later | huge | 1000 (the cap) |

Rule: days are counted as 24-hour local periods anchored at the due time of day in the library's `ZoneId`, rounded up (the code uses `ChronoUnit.DAYS.between` on zoned times). A daylight saving change does not make a day 23 or 25 hours: due 10:00 EDT on 31 Oct and returned 09:30 EST on 1 Nov is 24.5 real hours but under one local day, so 1 day late (a ceil of hours over 24 would say 2; a test pins this). `fine = days * rate`, but if `days > cap / rate` the fine is the cap, so there is no overflow.

**Transfer (F3).** Moving a copy from north to south is one CAS from `AVAILABLE(north)` to `IN_TRANSIT(south)`. Status and branch change in the same value, so there is no moment when the copy is "nowhere" and nothing to lock.

### The concurrency model

- **Shared state:** each copy. **Invariant:** one owner at a time. **Primitive:** one `AtomicReference<CopyState>` with CAS. **Why:** members only collide on the same copy, so there is no global lock. For CAS basics see [Java-poc 19](../../internal-pocs/Java-poc/concurrency/19-atomics-and-cas/).
- **CAS compares by reference.** `CopyState` is a record, and `AtomicReference.compareAndSet` uses `==`, not `equals`. The undo in `checkout` must pass the exact instance it installed. A freshly built equal record fails silently (our own test caught this bug).
- **Member slot:** a concurrent set (one title once) and a CAS loop on the count (never above the limit). Sixteen threads over eight titles end with exactly 3 loans and 3 loaned copies.
- **Hold queue:** one lock per title guards the decision "this free copy goes to the queue head or the shelf". Notifications are sent after the lock is released, so a slow notifier never runs while a lock is held and cannot block other members. The caller's own thread still pays for it, because notices are sent synchronously from `returnCopy`, `addCopy`, `receive`, `placeHold` and from the expiry sweep inside `checkout`. Use an async notifier adapter if that matters.
- **Return once:** `open.remove(loanId)` succeeds for one caller only, so the fine, the copy release and the count change run once. A second call finds the id in the `issued` map and gets `AlreadyReturned`.
- **Why not a lock for transfers:** a design that locks two branches needs a fixed order, see [Topic 08](../08-multi-object-atomicity/). Here one CAS makes the ordering question disappear.

### Code and tests

| File | What it is |
|---|---|
| `Library.java`, `LibraryException.java` | The interface and its error contract. |
| `InMemoryLibrary.java` | The implementation. |
| `CopyState.java`, `CopyStatus.java` | The one-value state of a copy. |
| `LibraryPolicy.java` | Limit, loan days, fine rate, cap, pickup window, zone. |
| `HoldNotifier.java`, `HoldNotice.java` | The notification port. |
| `Hook.java` | Test seam to pause a thread at a named point. |
| `LibraryTest.java` | 24 single-thread tests: fines, limits, holds, transfers. |
| `ConcurrencyTest.java` | 13 forced-interleaving and start-gate tests. |

---

## 3. The Gaps

### Follow-up ladder

| Follow-up | Design delta | Status |
|---|---|---|
| **F1** Holds queue with notification | FIFO queue per title, a copy returned goes to the head as ON_HOLD with a pickup deadline, expiry through the `Clock`, a `HoldNotifier` port. | Coded and tested. |
| **F2** Concurrent checkout of the last copy | One CAS per copy plus an ordered claim, undo on failure. | Coded and tested with a barrier hook. |
| **F3** Multi-branch transfers | Branch lives inside the CAS'd state, so a transfer is one CAS and `IN_TRANSIT` blocks checkout. | Coded and tested. |

### Misconceptions and common mistakes

| Myth | Reality |
|---|---|
| "`synchronized` on the book is enough." | It fixes the copy but not the three-part change (slot, copy, loan). Order and undo matter. |
| "Late means `>= due`." | At exactly the due instant it is on time. The test pins this. |
| "A check then a set is fine because the check just passed." | Another thread can act in between. Only CAS closes the gap. |
| "Let the notifier throw, the caller will retry the return." | The return already happened. Retrying gives `AlreadyReturned`. Swallow and count. |
| "Hold expiry needs a timer thread." | Not here. Any call re-checks the `Clock`, so tests need no waiting. |
| "Use `equals` to undo a CAS." | `compareAndSet` compares references. |

### Trade-offs and when not to

- Expiry on touch means a hold nobody touches stays ON_HOLD past its deadline until some call (or `sweepHolds`) runs. In production, run `sweepHolds` from a scheduler.
- The per-title lock serialises returns of the same title. That is fine: returns are rare compared with reads.
- `placeHold` refuses when a shelf copy exists at any branch. A real library may allow holds for a specific branch.

### Production and distributed delta

- A database: the copy row is updated with `UPDATE ... WHERE id=? AND status='AVAILABLE'` and a rows-affected check, which is the same CAS. Loan insert and copy update go in one transaction. See [Topic 08](../08-multi-object-atomicity/).
- Notifications through an outbox table plus a relay, and a DLQ for failures: see [event-processing-poc](../../internal-pocs/event-processing-poc/).
- Fines paid by users need idempotency keys ([Topic 11](../11-money-ids-idempotency/)).
- Same CAS-claim and hook-race pattern as the [parking lot](../14-parking-lot/).

### Measured facts

Setup: JDK 21.0.8, Apple silicon laptop. Test suites (37 tests) take about 0.06 s of test time and 1.8 s end to end with Maven. Mutation checks, each restored afterwards (suite green again):

| Mutation | Tests that fail |
|---|---|
| Claim CAS in `checkout` replaced by a plain `set` | `lastCopyRaceExactlyOneWins` (2 loans for 1 copy), `holderLosingTheExpiryRaceCannotTakeTheCopy`, `checkoutLosingToTransferCannotTakeTheCopy`, `manyMembersGetExactlyTheCopies` |
| Loan slot CAS replaced by increment | `lastLoanSlotRaceExactlyOneWins` (2 loans at the last slot) |
| Transfer CAS replaced by a plain `set` | `racingTransfersOfOneCopyExactlyOneWins` |
| Late days as ceil of real time over 24 h | `daysLateCountsLocalDaysAcrossDstEnd` (2 instead of 1) |

---

## 4. Official References

- [`AtomicReference` (Java 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html): `compareAndSet` compares by `==`.
- [`Clock` (Java 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html): inject time.
- [`ZonedDateTime.plusDays` (Java 21)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html): calendar days across daylight saving.
- Java Concurrency in Practice, ch. 15 (atomic variables and non-blocking sync).

---

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-5 | Ask the questions in section 1. Fix loan length, limit, fine rule. |
| 5-12 | Entities: title, copy, member, loan. Copy states. Write the interface and errors. |
| 12-22 | Checkout with CAS and the three-step order. Explain the last-copy race. |
| 22-30 | Return, fine rule with boundaries, `long` money. |
| 30-38 | Holds: FIFO, pickup window, notifier port. |
| 38-45 | Transfers, tests (barrier hook), production delta. |

---

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Last copy | Notes the race, adds `synchronized`. | One CAS per copy, proves it with a forced-interleaving test and a mutation check. |
| Atomicity | Updates copy and loan. | Orders slot, copy, loan; undoes in reverse; tests failure injection. |
| Fines | Days times rate. | States day rule and zone, half-open boundary, cap tested at its boundary, no overflow. |
| Holds | A list. | FIFO, deadline from the `Clock`, expiry passes on, notifier is a port that cannot break returns. |
| Transfers | Update the branch field. | Branch in the CAS'd state, in-transit blocks checkout, no lock order to get wrong. |

---

## 7. Key Takeaways

- Put everything that must change together into one immutable value and swap it with one CAS.
- A multi-record change needs a fixed order plus undo, and a test that injects a failure between steps.
- Pin time boundaries: at the due instant is on time, one nanosecond later is late.
- A notifier is a port; its failure must never undo a business fact.
- `AtomicReference.compareAndSet` compares references, not `equals`.

[← Topic 19: Turn-Based Games](../19-turn-based-game-engine/) · [Index](../README.md) · [Topic 21: Rate Limiter](../21-rate-limiter/)

**Self-grade: STRONG.** Remaining gap: no persistence or scheduler for hold expiry, and no REST adapter or renewals.
