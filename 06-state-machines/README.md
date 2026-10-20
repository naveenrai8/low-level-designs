# Topic 06: Explicit State Machines

"Can a cancelled order be shipped?" In most first drafts the honest answer is "depends which
method you call". State is a handful of booleans (`isPaid`, `isShipped`, `isCancelled`), every
method checks a different subset, and two concurrent requests (cancel and ship) can both pass
their checks. Vending machines, orders, seat holds, elevators, tickets and Jira workflows are
*all* state machines. Making the machine **explicit**, with one state field, one transition
table and one guarded `transition` method, turns "is this legal?" into a lookup. It makes races
fixable in one place, and it makes expiry and auditing natural instead of bolted on.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

### What breaks with implicit state

| Symptom | Cause |
|---|---|
| `isCancelled && isShipped` both true in production | n booleans give 2ⁿ combinations; nothing forbids the illegal ones |
| Cancel and ship race; both succeed | Check-then-act on state: `if (status == PAID) status = SHIPPED` isn't atomic |
| A hold never expires because nobody calls `expire()` | Time-based transitions modeled as background jobs that can be missed |
| "Who moved this ticket to Done?" can't be answered | No transition log |
| Adding a state (`ON_HOLD`) means auditing every method | Legality scattered across methods |

### What the interviewer is probing

- Do you **draw the state diagram first** (states, events, guards) before coding?
- Is illegal-transition handling **centralised**, with a clear error?
- Is a transition **atomic** under concurrency?
- Are **timeouts** modeled as transitions with injected time?

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

Terminal states (`DELIVERED`, `CANCELLED`) have **no outgoing edges**. Say that out loud. It's
the invariant most drafts miss.

### Shape 1 (default): enum + transition table

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

    public boolean canMoveTo(OrderStatus next) { return ALLOWED.get(this).contains(next); }
}
```

- The whole machine is **one readable table**. It's easy to test exhaustively: loop over all
  `values() × values()`.
- `EnumMap`/`EnumSet` are array/bit-vector backed. Lookups are cheap, and iteration follows
  declaration order.
- Adding `ON_HOLD` means one row plus edges. Any exhaustive `switch` over `OrderStatus` without
  a `default` then fails to compile, which points you at every place that must handle it.

### Shape 2: State pattern (a class per state)

```java
interface VendingState {
    VendingState insertCoin(Machine m, Money coin);
    VendingState select(Machine m, Slot slot);
    VendingState dispense(Machine m);
}
// Idle, HasMoney, Dispensing, OutOfStock each implement only the events that make sense
// and return the next state; the rest throw IllegalStateException.
```

Use it when **behaviour per state differs a lot**, e.g. a vending machine where `select` in
`HasMoney` computes change and in `Idle` prompts for money. The cost is N classes, and legality is
spread across them rather than visible in one table.

### Shape 3: sealed types when states carry different data

```java
sealed interface SeatState permits Available, Held, Booked {}
record Available() implements SeatState {}
record Held(UserId by, Instant expiresAt) implements SeatState {}
record Booked(UserId by, BookingId booking) implements SeatState {}
```

`Held` *must* have an expiry and `Booked` *must* have a booking ID. The type makes "held with no
expiry" unrepresentable, which enums alone can't do (Topic 03).

| | Enum + table | State pattern | Sealed types |
|---|---|---|---|
| Legality visible in one place | ✅ | ❌ | partially, via `switch` |
| Per-state data | ❌ (side fields) | ✅ | ✅ |
| Per-state behaviour | `switch` | ✅ polymorphism | `switch` |
| 45-min cost | lowest | highest | low |

### Guarding illegal transitions, atomically

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

- `ship()` = `transition(PAID, SHIPPED)` and `cancel()` = `transition(PAID, CANCELLED)`. If
  both race, **exactly one CAS succeeds** and the loser gets a clear error.
- The broken version, `if (status == PAID) status = SHIPPED;`, lets both pass the check. This
  is the same check-then-act race as `../21-rate-limiter`'s `ConcurrencyTest`, where measured
  over-grants ran as high as 6.4× the limit.
- **CAS works when the state is a single reference.** When a transition must also update other
  fields or objects (decrement stock, write the log, release a seat), use a **per-entity lock**
  (`synchronized` on the entity, or `ConcurrentHashMap.compute` on its ID) so the whole update
  is one critical section. Multi-entity transitions are Topic 08.
- Whether a lost race is an exception or a result value (`TransitionResult.Conflict`) is a
  Topic 04 decision. For user-facing "already cancelled", prefer a value.

### Timeouts and expiry are transitions, with injected time

```java
public SeatState current(Clock clock) {                       // lazy expiry: evaluated on read
    if (state instanceof Held h && !clock.instant().isBefore(h.expiresAt())) {
        return new Available();                                // an expired hold IS available
    }
    return state;
}
```

- **Lazy evaluation on access** (as with the rate limiter's refill) means a missed background
  sweep can never leave a seat stuck in `Held`. A sweeper is then only cleanup and
  notification, not correctness.
- **Inject `java.time.Clock`.** Tests use `Clock.fixed(...)` or a mutable fake and step time
  past `expiresAt` without sleeping (Topic 10).
- Boundaries need a decision: is a hold that expires at `t` still valid at exactly `t`? Pick
  one (here: no), write it in the Javadoc, and test it.

### Auditability: the transition log

```java
record Transition(OrderStatus from, OrderStatus to, Instant at, String actor, String reason) {}
```

Append one per successful transition, *inside the same critical section* as the state change.
A log written outside it can show transitions in an order that never happened. This is what
answers "who moved this to Done?". At scale it becomes an event log or outbox (see Topic 13 and
event-processing-poc).

### Examples you'll be asked

- **Vending machine:** `Idle → HasMoney → Dispensing → Idle`, with `OutOfStock` and refunds.
  State pattern is reasonable here.
- **Order lifecycle:** the enum table above, plus CAS.
- **Seat hold / booking:** sealed types plus lazy expiry (Topic 31).
- **Jira-like workflow:** the *transition table is data*, configured per project with guards
  ("only assignee can resolve") and post-actions. That's the [workflow engine](../42-workflow-engine/)
  topic (planned).

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| Multiple booleans for state | Illegal combinations are representable |
| `setStatus(s)` public setter | Bypasses the table. Every caller can make any transition |
| Check-then-set without atomicity | Two racing transitions both succeed |
| `volatile status` as the "fix" | Gives visibility, not atomicity. The check and the write are still two steps |
| Expiry only via a scheduled job | A missed or slow job leaves entities stuck; tests need `sleep` |
| `System.currentTimeMillis()` inside the entity | Untestable; wall-clock jumps |
| Throwing a generic `RuntimeException("invalid")` | The caller can't tell an illegal edge (a bug) from a lost race (retry/re-read) |
| A State pattern with 8 classes for a 4-state enum | 45-minute budget gone. Legality invisible |
| Forgetting terminal states | `CANCELLED → PAID` becomes possible after a refactor |
| Writing the audit log outside the lock | The log order disagrees with the real order |

### "I thought X, but actually Y"

- **"An enum is a state machine."** An enum is the *set of states*. The machine is the
  **transition table plus the guarded transition method**.
- **"The State pattern is the 'proper' way."** It's one way. Interviewers reward the shape whose
  legality you can explain in one glance, which is usually the table.
- **"CAS solves all concurrency here."** Only for single-reference updates. Side effects
  (inventory, log, notifications) need a critical section, or an outbox if they leave the
  process.

### When NOT to

- **Two states with no illegal transitions** (`enabled`/`disabled`) don't need a table. A
  `boolean` is honest here.
- **Don't build a generic configurable FSM engine** unless the prompt is literally "design a
  workflow engine". Hard-code the table for the domain in front of you.
- **Don't encode business *rules* (pricing, eligibility) as states.** States are lifecycle
  stages. Rules are policies (Strategy, Topic 05).

### Interview move

Before any code, draw the diagram and say: *"Five states, two terminal. Legality lives in one
table. Transitions are compare-and-set from the expected state, so racing cancel and ship can't
both win. Expiry is evaluated lazily against an injected clock, and every transition appends to
an audit log under the same lock."* Then code the table first. It's 10 lines and it anchors
everything after.

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

1. Every lifecycle is a state machine. **Draw it first**, terminal states included.
2. Default shape: **enum + `EnumMap<State, Set<State>>` table + one guarded `transition`.**
3. Use **sealed types** when states carry different data, and the **State pattern** only when
   per-state behaviour diverges a lot.
4. Transitions must be **atomic**: CAS from the expected state for a single reference, a
   per-entity lock when side effects come along.
5. Distinguish **illegal edge** (a bug) from **lost race** (re-read or retry) in what you return.
6. Model **expiry as a transition evaluated lazily** against an **injected `Clock`**.
7. Append an **audit record inside the same critical section** as the state change.
8. `volatile` is not a fix for check-then-act.

---

[← Topic 05: Patterns That Matter](../05-patterns-that-matter/) · [Index](../README.md) · [Topic 07: Concurrency Primitives →](../07-concurrency-primitives/)

**Self-grade: STRONG.** It compares the three shapes with a table, gives an atomic CAS transition
with the explicit race it prevents (tied to the measured rate-limiter over-grant), lazy expiry
with an injected clock, audit-log ordering, when-not-to and an interview script. The CAS and
expiry snippets aren't backed by a runnable module in this topic.
