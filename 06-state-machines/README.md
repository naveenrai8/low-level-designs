# Topic 06: Explicit State Machines

"Can a cancelled order be shipped?" In most first drafts the honest answer is "it depends which method you call". The state is a handful of booleans (`isPaid`, `isShipped`, `isCancelled`), and every method checks a different few of them. Two requests at the same time (cancel and ship) can both pass their checks. Vending machines, orders, seat holds, elevators, tickets and Jira workflows are *all* state machines. A *state machine* is a thing that is always in exactly one state and moves between states by fixed rules. Make it **explicit**: one state field, one transition table, and one guarded `transition` method. Then "is this legal?" becomes a lookup, races can be fixed in one place, and expiry and audit fit in naturally.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

### What breaks with hidden state

| Symptom | Cause |
|---|---|
| `isCancelled && isShipped` are both true in production | n booleans give 2ⁿ combinations, and nothing forbids the illegal ones |
| Cancel and ship race, and both succeed | Check-then-act on state: `if (status == PAID) status = SHIPPED` is not one atomic step |
| A hold never expires because nobody calls `expire()` | Timeouts are done by background jobs that can be missed |
| "Who moved this ticket to Done?" has no answer | There is no log of transitions |
| Adding a state (`ON_HOLD`) means checking every method | The rules of what is legal are spread over many methods |

### What the interviewer is probing

- Do you **draw the state diagram first** (states, events, guards) before you code? A *guard* is a condition that must hold for a move.
- Is the handling of illegal moves **in one place**, with a clear error?
- Is a transition **atomic** (all-or-nothing, with no other thread sneaking in) under concurrency?
- Are **timeouts** modelled as transitions, using injected time?

---

## 2. How It Solves It

### Draw it first (order lifecycle)

```
             pay              ship             deliver
  CREATED ─────────► PAID ─────────► SHIPPED ─────────► DELIVERED
     │                 │
     │ cancel          │ cancel (refund)
     ▼                 ▼
  CANCELLED ◄──────────┘          SHIPPED ─► RETURN_REQUESTED  (follow-up F1)
```

Terminal states (`DELIVERED`, `CANCELLED`) have **no arrows out**. Say that out loud. It is the rule most drafts miss.

### Shape 1 (default): enum plus transition table

```java
public enum OrderStatus {
    CREATED, PAID, SHIPPED, DELIVERED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);
    static {
        ALLOWED.put(CREATED,   EnumSet.of(PAID, CANCELLED));
        ALLOWED.put(PAID,      EnumSet.of(SHIPPED, CANCELLED));
        ALLOWED.put(SHIPPED,   EnumSet.of(DELIVERED));
        ALLOWED.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));   // terminal
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));   // terminal
    }

    /** Says whether the table allows going from this state to {@code next}. */
    public boolean canMoveTo(OrderStatus next) { return ALLOWED.get(this).contains(next); }
}
```

- The whole machine is **one readable table**. It is easy to test fully: loop over every pair in `values() × values()`.
- `EnumMap` and `EnumSet` are backed by arrays and bit sets, so lookups are cheap and iteration follows declaration order.
- Adding `ON_HOLD` means one new row plus its arrows. Any `switch` over `OrderStatus` with no `default` then fails to compile, which points you at every place that must handle it.

### Shape 2: the State pattern (one class per state)

```java
interface VendingState {
    VendingState insertCoin(Machine m, Money coin);
    VendingState select(Machine m, Slot slot);
    VendingState dispense(Machine m);
}
// Idle, HasMoney, Dispensing, OutOfStock each implement only the events that make sense
// and return the next state; the rest throw IllegalStateException.
```

Use it when **behaviour differs a lot per state**. In a vending machine, `select` in `HasMoney` works out change, while in `Idle` it asks for money. The cost is N classes, and the rules of what is legal are spread across them instead of sitting in one table.

### Shape 3: sealed types when states carry different data

A *sealed* interface allows only the listed implementations, so the compiler knows every case.

```java
sealed interface SeatState permits Available, Held, Booked {}
record Available() implements SeatState {}
record Held(UserId by, Instant expiresAt) implements SeatState {}
record Booked(UserId by, BookingId booking) implements SeatState {}
```

`Held` *must* have an expiry and `Booked` *must* have a booking ID. The type makes "held with no expiry" impossible to build, which an enum alone cannot do (Topic 03).

| | Enum + table | State pattern | Sealed types |
|---|---|---|---|
| Legal moves visible in one place | yes | no | partly, via `switch` |
| Data per state | no (extra fields) | yes | yes |
| Behaviour per state | `switch` | yes, by polymorphism | `switch` |
| Cost in a 45-minute round | lowest | highest | low |

### Blocking illegal transitions, atomically

First the race, step by step. An order is `PAID`, and a cancel request and a ship request arrive at the same moment.

| Step | Cancel thread | Ship thread | Status |
|---|---|---|---|
| 1 | reads `PAID`, check passes | reads `PAID`, check passes | PAID |
| 2 | writes `CANCELLED` | | CANCELLED |
| 3 | | writes `SHIPPED` | SHIPPED (a cancelled order shipped) |

With compare-and-set (CAS: one step that says "change it only if it is still what I expect"), step 3 fails because the status is no longer `PAID`. The ship thread gets an error.

```java
public final class Order {
    private final OrderId id;
    private final AtomicReference<OrderStatus> status = new AtomicReference<>(OrderStatus.CREATED);

    /** Atomic compare-and-set: of two racing transitions from the same state, exactly one wins. */
    public void transition(OrderStatus from, OrderStatus to) {
        if (!from.canMoveTo(to)) {
            throw new IllegalArgumentException(from + " -> " + to + " is not a legal transition"); // caller/program bug
        }
        if (!status.compareAndSet(from, to)) {
            throw new IllegalStateException("expected " + from + " but was " + status.get());     // lost a race / stale read
        }
    }
}
```

- `ship()` is `transition(PAID, SHIPPED)` and `cancel()` is `transition(PAID, CANCELLED)`. If both race, **exactly one CAS succeeds** and the loser gets a clear error.
- The broken version, `if (status == PAID) status = SHIPPED;`, lets both pass the check. This is the same check-then-act race as `../21-rate-limiter`'s `ConcurrencyTest`, where measured over-grants ran as high as 6.4× the limit.
- **CAS works when the state is one reference.** If a move must also change other fields or objects (take stock, write the log, free a seat), use a **lock per entity** (`synchronized` on the entity, or `ConcurrentHashMap.compute` on its ID). Then the whole update is one critical section, meaning one block only one thread can be in. Moves across several entities are Topic 08.
- Whether a lost race is an exception or a result value (`TransitionResult.Conflict`) is a Topic 04 choice. For a user-facing "already cancelled", prefer a value.

### Timeouts and expiry are transitions, using injected time

```java
/** Gives the current state, treating an expired hold as already free (checked lazily, on read). */
public SeatState current(Clock clock) {
    if (state instanceof Held h && !clock.instant().isBefore(h.expiresAt())) {
        return new Available();                                // an expired hold IS available
    }
    return state;
}
```

- **Lazy checking on read** (like the rate limiter's refill) means a missed background sweep can never leave a seat stuck in `Held`. The sweeper is then only cleanup and notification, not needed for correctness.
- **Inject `java.time.Clock`.** Tests use `Clock.fixed(...)` or a fake clock they can move, and step time past `expiresAt` without sleeping (Topic 10).
- Boundaries need a decision: is a hold that expires at `t` still valid at exactly `t`? Pick one (here: no), write it in the Javadoc, and test it.

### Audit: the transition log

```java
record Transition(OrderStatus from, OrderStatus to, Instant at, String actor, String reason) {}
```

Add one record per successful transition, *inside the same critical section* as the state change. A log written outside it can show moves in an order that never happened. This is what answers "who moved this to Done?". At scale it becomes an event log or an outbox (see Topic 13 and event-processing-poc).

### Examples you'll be asked

- **Vending machine:** `Idle → HasMoney → Dispensing → Idle`, with `OutOfStock` and refunds. The State pattern is reasonable here.
- **Order lifecycle:** the enum table above, plus CAS.
- **Seat hold or booking:** sealed types plus lazy expiry (Topic 31).
- **Jira-like workflow:** the *transition table is data*, set up per project with guards ("only the assignee can resolve") and follow-up actions. That is the [workflow engine](../42-workflow-engine/) topic (planned).

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| Several booleans for state | Illegal combinations can exist |
| A public `setStatus(s)` | It skips the table, so any caller can make any move |
| Check, then set, with no atomic step | Two racing moves both succeed |
| `volatile status` as the "fix" | It gives visibility, not atomicity. The check and the write are still two steps |
| Expiry only through a scheduled job | A missed or slow job leaves entities stuck, and tests need `sleep` |
| `System.currentTimeMillis()` inside the entity | Cannot be tested, and the wall clock can jump |
| Throwing a bare `RuntimeException("invalid")` | The caller can't tell an illegal move (a bug) from a lost race (re-read or retry) |
| A State pattern with 8 classes for a 4-state enum | The 45-minute budget is gone, and the rules are hard to see |
| Forgetting terminal states | `CANCELLED → PAID` becomes possible after a refactor |
| Writing the audit log outside the lock | The log order disagrees with the real order |

### "I thought X, but actually Y"

- **"An enum is a state machine."** An enum is only the *list of states*. The machine is the **transition table plus the guarded transition method**.
- **"The State pattern is the 'proper' way."** It is one way. Interviewers reward the shape whose rules you can explain at a glance, which is usually the table.
- **"CAS solves all concurrency here."** Only for updates to a single reference. Side effects (stock, log, notifications) need a critical section, or an outbox if they leave the process.

### When NOT to

- **Two states with no illegal moves** (`enabled`/`disabled`) don't need a table. A `boolean` is honest here.
- **Don't build a generic, configurable state machine engine** unless the prompt is literally "design a workflow engine". Hard-code the table for the domain in front of you.
- **Don't put business *rules* (pricing, eligibility) into states.** States are stages of a life cycle. Rules are policies (Strategy, Topic 05).

### Interview move

Before any code, draw the diagram and say: *"Five states, two terminal. The rules of what is legal live in one table. Moves are compare-and-set from the expected state, so racing cancel and ship can't both win. Expiry is checked lazily against an injected clock, and every move adds to an audit log under the same lock."* Then code the table first. It is 10 lines and it anchors everything after.

---

## 4. Official References

- `EnumMap`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/EnumMap.html
- `EnumSet`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/EnumSet.html
- `AtomicReference.compareAndSet`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html
- `java.time.Clock` (inject time; `Clock.fixed` for tests): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
- JEP 441, Pattern Matching for `switch`: https://openjdk.org/jeps/441
- Gamma et al., *Design Patterns*: the State pattern.
- Joshua Bloch, *Effective Java* (3rd ed.): "Use EnumMap instead of ordinal indexing" (its example is a phase-transition table).

---

## 5. Key Takeaways

1. Every life cycle is a state machine. **Draw it first**, terminal states included.
2. Default shape: **enum + `EnumMap<State, Set<State>>` table + one guarded `transition`.**
3. Use **sealed types** when states carry different data. Use the **State pattern** only when behaviour per state differs a lot.
4. Transitions must be **atomic**: CAS from the expected state for a single reference, a lock per entity when side effects come along.
5. Tell an **illegal move** (a bug) apart from a **lost race** (re-read or retry) in what you return.
6. Model **expiry as a transition checked lazily** against an **injected `Clock`**.
7. Add an **audit record inside the same critical section** as the state change.
8. `volatile` is not a fix for check-then-act.

---

[← Topic 05: Patterns That Matter](../05-patterns-that-matter/) · [Index](../README.md) · [Topic 07: Concurrency Primitives →](../07-concurrency-primitives/)

**Self-grade: STRONG.** It compares the three shapes with a table, gives an atomic CAS transition with the explicit race it prevents (tied to the measured rate-limiter over-grant), lazy expiry with an injected clock, audit-log ordering, when-not-to and an interview script. The CAS and expiry snippets aren't backed by a runnable module in this topic.
