# Topic 15: Vending Machine

"Design a vending machine" looks like a state-machine drawing exercise. The interviewer is waiting for three things the drawing hides: illegal moves must be impossible (not just unlikely), giving change is a small algorithm problem where the obvious answer is wrong, and a hardware failure halfway through must not lose anybody's money.

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
| **State** | Which situation the machine is in right now: IDLE, SELECTING, PAID or DISPENSING. |
| **Event** | Something that tries to move the machine: a button, a coin, or a report from the motor. |
| **Transition table** | A list of every allowed (state, event) pair and where it may lead. Anything not on the list is refused. Topic 06 teaches this shape in depth: [state machines](../06-state-machines/). |
| **Minor units** | Money counted in the smallest coin (cents) as a whole `long`. $1.25 is `125`. Never `double`. See [money](../11-money-ids-idempotency/). |
| **Change** | The coins handed back when the customer paid more than the price. |
| **Coin box (float)** | The machine's own stock of coins, used to make change. |
| **Session** | One customer's visit: from picking a product until the sale, cancel or refund. |
| **Greedy** | "Always take the biggest coin that still fits." Fast, but wrong for some coin sets. |
| **DP (dynamic programming)** | Solve small amounts first and reuse them to solve bigger ones. |
| **Jam** | The dispenser failed and the product did not come out. |
| **Conserve money** | Inserted = refunded + kept, to the last cent. |

---

## 1. The Problem

### What breaks without a design

A machine written as a pile of `if (hasMoney && productSelected)` flags lets a customer insert coins while the motor is running, press dispense twice, or cancel mid-drop. Each bug is a lost or doubled sale. And a naive change routine either hands back too many coins, or says "no change possible" when a perfectly good combination exists.

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Explicit states | A transition table, so an illegal move is rejected by construction |
| Algorithm judgement | You know greedy change-making is wrong and can say why |
| Failure thinking | A jam after taking money is handled, and money is conserved |
| Money correctness | `long` minor units, exact equality in tests |
| Seams | Hardware, stock and telemetry are interfaces, so tests need no real machine |

### v1 requirements (say these out loud)

1. Flow: IDLE, then SELECTING (product picked, collecting coins), then PAID (price covered), then DISPENSING, then back to IDLE.
2. Accepts a fixed set of coin values. Cancel is allowed before dispensing and returns the exact coins inserted.
3. Gives change using only the coins the machine really holds, with the fewest coins.
4. If exact change cannot be made, it refuses the sale before touching the product and returns the money.
5. Thread-safe: one customer at a time, even if two threads press buttons.

### Clarifying questions (with the answer I assume)

| Question | Assumed answer | Why it matters |
|---|---|---|
| Notes or only coins? | Coins only | Notes need a validator; same change logic |
| Do we keep the customer's coins as change? | Yes, inserted coins join the box on a sale | Makes more change combinations possible |
| Overpay allowed? | Yes, until the price is covered; no more coins after PAID | Keeps the table small |
| What if the dispenser jams? | Refund everything, keep nothing | F2 |
| Several machines? | Each machine is independent | Fleet view is F3 |

### Out of scope for v1

Card payments, note validators, product search UI, restocking workflow, and any HTTP layer (this topic is core only).

---

## 2. How It Solves It

### Entities and invariants

| Type | Kind | Invariant |
|---|---|---|
| `State`, `Event` | enums | Four states, six events |
| `TransitionTable` | the rulebook | Only 7 of the 24 (state, event) pairs are legal |
| `Product(code, name, price)` | record | `price > 0` |
| `Payout` | record | A coin-to-count map, no zero or negative counts, `total()` in minor units |
| `SaleResult` | record | `inserted() = returned.total() + kept`, always |
| `Inventory` | port | `take` is called only after the product really dropped |
| `Dispenser` | port | Throws `DispenseJamException` when nothing came out |
| `ChangeMaker` | pure function | Fewest coins within real stock, or empty |
| `DefaultVendingMachine` | the component | One lock; the state changes only along a table edge |

### Public interface first

```java
public interface VendingMachine {
    State state();
    long balance();
    void select(String code);          // IDLE -> SELECTING
    long insert(long coin);            // SELECTING -> SELECTING or PAID
    Payout cancel();                   // SELECTING/PAID -> IDLE, exact coins back
    SaleResult dispense();             // PAID -> DISPENSING -> IDLE
    void loadCoins(long coin, int n);  // operator, any state
    Payout coinBox();
}
```

Error contract: an event that is illegal now throws `IllegalTransitionException` and changes nothing. A bad coin or unknown product throws `IllegalArgumentException`. A sold-out product throws `SoldOutException`. A failed sale (no change, jam) is **not** an exception: it is a normal `SaleResult` with the money returned.

### State diagram (whiteboard version)

```
            select                 insert (balance >= price)
   IDLE ------------> SELECTING ------------------------------> PAID
    ^  ^                |  ^  insert (still short)               |  |
    |  |    cancel      |  +-------------+                       |  | dispense
    |  +----------------+                                cancel  |  v
    |  <-----------------------------------------------------+  DISPENSING
    +---------------- COMPLETE or JAM ------------------------------+
         (dispense with no exact change also goes PAID -> IDLE with a full refund)
```

The table below is the source of truth; there is no separate UML file for this small topic.

### The transition table (all 24 pairs)

| State \ Event | SELECT | INSERT | CANCEL | DISPENSE | COMPLETE | JAM |
|---|---|---|---|---|---|---|
| IDLE | SELECTING | no | no | no | no | no |
| SELECTING | no | SELECTING or PAID | IDLE | no | no | no |
| PAID | no | no | IDLE | DISPENSING or IDLE | no | no |
| DISPENSING | no | no | no | no | IDLE | IDLE |

"no" means `IllegalTransitionException`. The test checks all 24 pairs, not a sample: the expected-legal list has 7 entries and the other 17 must throw. COMPLETE and JAM come from the machine, never from a customer, so they are only reachable inside `dispense()`. While the motor runs, the test plugs in a dispenser that calls back into the machine on the same thread and checks that SELECT, INSERT, CANCEL and DISPENSE are all refused (the lock is reentrant, so the probe gets in, and the table says no).

### Change-making: a timeline first

Coins {1, 3, 4}, change 6.

| Step | What happens | Result |
|---|---|---|
| Greedy, step 1 | Biggest coin that fits is 4 | left 2 |
| Greedy, step 2 | Biggest that fits is 1, twice | 4+1+1, three coins |
| DP | Tries all ways and remembers the fewest coins for each amount 0 to 6 | 3+3, two coins |

Now the stock-limited case: one 5 and three 2s, change 6.

| Step | What happens | Result |
|---|---|---|
| Greedy, step 1 | Takes the 5 | left 1 |
| Greedy, step 2 | No 1 in stock | **gives up: no answer** |
| DP | Knows 2+2+2 reaches 6 within stock | 2+2+2 |

The DP in `ChangeMaker.make`: process one denomination at a time. For each amount `a`, `best[a]` is the fewest coins that make `a` using the denominations seen so far, trying `k = 0..stock` coins of the current one. Using one denomination per layer is what stops a coin from being used more times than it is in stock. Cost: O(amount x coins in stock) time, O(denominations x amount) memory. For vending amounts (under a few thousand cents) this is well under a millisecond (estimate, not benchmarked). A guard rejects amounts above 1 million minor units.

Why not "greedy when the coin set is canonical"? Real currencies (1, 5, 10, 25) are canonical, but the machine's **stock** is not: running out of 5s makes the available set behave like a non-canonical one. The DP over the actual stock is correct in every case.

### How a sale is decided

1. `dispense()` checks the table (PAID only).
2. It asks the inventory again: an operator may have restocked to zero since `select`. Out of stock is refused with a full refund (`REFUSED_OUT_OF_STOCK`).
3. It builds the pool = coin box + this customer's coins and asks `ChangeMaker` for `balance - price`.
4. No answer: refuse. Return the customer's exact coins. The product and the box are untouched.
5. Answer found: state goes to DISPENSING and the dispenser runs.
6. Jam: refund exactly the customer's coins, keep nothing, do not decrement stock.
7. Success: merge the session into the box, pay the change out of it, then take one unit of stock. The session always ends in a `finally`, so the machine can never stay stuck in DISPENSING.

The customer's coins are kept in a separate **session** pile, so a refund can never fail: the exact coins are still there.

### The money rule, as a timeline

Price 125, coins 100+100 inserted (200), box holds three 25s.

| Case | Returned | Kept | Check |
|---|---|---|---|
| Sale | 75 (three 25s) | 125 | 75 + 125 = 200 |
| Jam | 200 (both 100s) | 0 | 200 + 0 = 200 |
| Box empty | 200 (both 100s) | 0 | refused before dispensing, 200 + 0 = 200 |

`SaleResult.inserted()` is computed as `returned.total() + kept` and tests compare it with `assertEquals(long, long)` to the coins actually inserted.

### Concurrency model

- **Shared state:** the state, the session, the coin box and the stock.
- **Invariant:** one customer at a time; every state change follows a table edge.
- **Inventory is not behind the machine lock.** Operators restock straight on the inventory, so `ShelfInventory` and `RecipeInventory` have synchronized methods, and the machine re-checks stock just before the motor. This is simpler than routing every restock through the machine, and the machine already copes with the answer changing. Coins are different: `loadCoins` goes through the machine lock, because the box is machine state.
- **Primitive:** one `ReentrantLock` around each public method. Reentrant on purpose: a dispenser callback on the same thread must see the machine as DISPENSING, not deadlock. See [locks in the Java guide](../../internal-pocs/Java-poc/concurrency/16-reentrantlock/).
- **Why not per-field atomics:** the state, balance and session change together, so only a lock makes them one step.
- **Tests:** a start-gate latch with 16 threads racing to select: exactly 1 wins, 15 are refused. 8 threads each trying 5 coins of 5 against a 125 price: exactly 25 accepted, balance exactly 125. A deliberately broken `CheckThenActPaymentDesk` (check, gap, write) with a barrier in the gap forces the overpay every run (150 on a 125 price).

### Code and tests

| File | Role |
|---|---|
| [`TransitionTable`](src/main/java/com/lld/vending/TransitionTable.java) | The rulebook |
| [`DefaultVendingMachine`](src/main/java/com/lld/vending/DefaultVendingMachine.java) | The machine |
| [`ChangeMaker`](src/main/java/com/lld/vending/ChangeMaker.java) | DP change-making |
| [`RecipeInventory`](src/main/java/com/lld/vending/RecipeInventory.java) / [`ShelfInventory`](src/main/java/com/lld/vending/ShelfInventory.java) | Coffee (F1) and snack stock |
| [`Telemetry`](src/main/java/com/lld/vending/Telemetry.java) | Remote seam (F3) |
| [`TransitionTableTest`](src/test/java/com/lld/vending/TransitionTableTest.java) | All 24 pairs |
| [`ChangeMakerTest`](src/test/java/com/lld/vending/ChangeMakerTest.java) | Greedy failures, 3000-case differential against brute force |
| [`VendingMachineTest`](src/test/java/com/lld/vending/VendingMachineTest.java) | Sales, refunds, no-change refusal, jams |
| [`RecipeInventoryTest`](src/test/java/com/lld/vending/RecipeInventoryTest.java) | Coffee recipes |
| [`CheckThenActPaymentDesk`](src/test/java/com/lld/vending/CheckThenActPaymentDesk.java) | The named bug class: check then act, broken on purpose |
| [`ConcurrencyTest`](src/test/java/com/lld/vending/ConcurrencyTest.java) | Racing threads and the forced-interleaving proof |

---

## 3. The Gaps

### F1: Coffee variant (coded)

A drink is a **recipe**: ingredient name to amount. `RecipeInventory` implements the same `Inventory` port. A drink is in stock only if every ingredient is there in full, and `take` removes all ingredients together or none. Example: espresso needs 30 ml water and 18 g coffee; with 100 g of coffee the machine sells exactly 5 espressos, then `select` throws `SoldOutException` (tested). The state machine is unchanged, which is the point of the port.

### F2: Partial-failure refunds (coded)

The danger is order of operations. If you decrement stock or move money **before** the dispenser finishes, a jam leaves you wrong. So: dispense first, then take stock, then move money. On a jam the session coins go back exactly, stock and box are unchanged. **Fault policy:** before the motor, no change or no stock means a full refund. During the motor, any failure (jam, runtime exception, even an `Error`) counts as a jam and means a full refund. The cost: a fault that happens after the product really dropped also refunds, so the machine can lose one product. We accept that, because paying for nothing is worse than a lost snack. **After the drop** the sale stands: if the stock count then throws, the money still moves, the session still ends, and a `STOCK_FAULT` event is sent for an operator to check.

### F3: Remote telemetry seam (coded, small)

`Telemetry.emit(MachineEvent)` with SALE, REFUSED_NO_CHANGE and JAM events stamped from the injected `Clock`. The machine swallows exceptions from it: monitoring must never break a sale, a jam refund or a no-change refusal (all tested). Production delta: do not call the network under the machine lock. Put events in a local queue, send them from another thread, and use an outbox so a power cut does not lose a JAM report. See [outbox in the event-processing guide](../../internal-pocs/event-processing-poc/core/).

### Myth to Reality

| Myth | Reality |
|---|---|
| "Greedy change is fine" | It fails on {1,3,4} for 6, and with limited stock it can fail to find any answer that exists |
| "If the switch statement covers the cases, illegal moves are handled" | Only an explicit table that is tested on every pair proves it |
| "A jam is rare, log it" | A jam after taking money is a refund bug waiting to happen; decide the order of steps first |
| "Round money to dollars with double" | Use `long` cents; equality tests are exact |
| "Stock was checked at select, so it is fine" | Stock can change before the motor runs; check again |
| "Refuse after dispensing if no change" | Check change **before** the motor runs |

### Trade-offs and when not to

- DP is more code than greedy. For a fixed canonical coin set with unlimited stock, greedy is enough; the DP is for a stock-limited box.
- One lock per machine is fine: one customer at a time. A shared server for many machines must lock per machine id, not globally.
- A single enum with a table is enough here. Use the State pattern (a class per state) only when states carry different behaviour and data (see Topic 06).

### Production and distributed delta

Persist the session and box so a power cut can refund correctly on reboot. Make the refund idempotent (see Topic 11). Fleet management means events go through a durable queue and the server is the one with the dashboard; the machine keeps working offline.

---

## 4. Official References

- State pattern overview: https://refactoring.guru/design-patterns/state
- Change-making problem (greedy vs DP): https://en.wikipedia.org/wiki/Change-making_problem
- `ReentrantLock`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html
- Cormen, Leiserson, Rivest, Stein, *Introduction to Algorithms*, chapter 15 (dynamic programming) and the coin-change exercises.
- Topic 06 (state machines), Topic 10 (time and testability), Topic 11 (money, ids, idempotency) in this guide.

---

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-5 | Clarify: coins only, stock-limited change, what happens on a jam |
| 5-12 | Draw states and events; write the transition table |
| 12-20 | Write the interface and error contract |
| 20-32 | Code the machine; explain why change is checked before dispensing |
| 32-40 | Change-making: show greedy failing on {1,3,4}, then the DP |
| 40-45 | Jam refund and money conservation; raise telemetry and coffee follow-ups |

If behind at minute 25, skip telemetry and describe the DP out loud with the {1,3,4} example.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| States | Enum plus switch | Explicit table, every pair tested, illegal moves change nothing |
| Change | Greedy | Shows the greedy counter-example, DP over real stock, tested against brute force |
| Failure | Handles cancel | Orders steps so a jam conserves money; refuses before dispensing when no change |
| Money | `int` dollars | `long` minor units, exact assertions |
| Seams | Hard-coded hardware | `Dispenser`, `Inventory`, `Telemetry`, `Clock` injected |
| Drives | Waits for follow-ups | Raises jam and no-change cases before being asked |

## 7. Key Takeaways

1. Put every legal (state, event) pair in one table and test all of them. Illegal moves then fail loudly and change nothing.
2. Greedy change-making is wrong on non-canonical sets and on limited stock. Use DP over the coins actually in the box.
3. Check for exact change **before** dispensing. A refusal is a normal result, not an exception.
4. Keep the customer's coins separate so a refund cannot fail. Inserted = refunded + kept, exactly.
5. Dispense first, then decrement stock and move money. A jam must not change inventory.
6. Hardware, stock and monitoring are interfaces. Monitoring failures never block a sale.

---

**Self-grade: STRONG.** It has the full spine, every table cell tested, change-making proven against brute force on 3000 random small cases, jam and no-change money conservation tested exactly, and F1 to F3 coded. Remaining gap: persistence across a power cut and a real outbox for telemetry are described, not coded.

[← Topic 14: Parking Lot](../14-parking-lot/) · [Index](../README.md) · [Topic 16: Elevator →](../16-elevator/)
