# Topic 05: The Patterns That Actually Matter (and When Not to Use Them)

Interviewers don't score "used six GoF patterns". They score whether the follow-up was cheap. A
candidate who wraps a parking lot in a `SpotFactoryBuilderSingleton` has spent 10 minutes on
indirection that no follow-up needed. A candidate who put **one** interface where pricing varies
handles "add weekend pricing" in two minutes. This topic covers the eight patterns that come up
in LLD rounds. For each one: the problem it solves, the 45-minute-sized shape, and the context
where it's the wrong call. Pattern-stuffing reads as mid-level, and naming the pattern you
*didn't* use, and why, reads as staff.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

A design pattern is a named answer to a **recurring change**. Without one, the change spreads
through `if/else` chains: every new vehicle type, pricing rule or notification channel edits the
same five methods, and one gets missed. With too many, the code is all indirection, the
interviewer can't follow it, and you run out of time before any logic exists.

What the interviewer probes:

- Can you **spot the axis of change** in the prompt? ("pricing may vary", "new piece types",
  "undo")
- Do you apply a pattern **at that axis only**?
- Can you say **what it costs**: an extra type, indirection, harder debugging?

---

## 2. How It Solves It

### Prompt → the pattern that genuinely helps

| Prompt signal | Pattern | Where |
|---|---|---|
| "support multiple algorithms / policies / pricing rules" | **Strategy** | rate limiter algorithm, parking pricing, elevator scheduling, order matching rule |
| "behaviour depends on current mode; illegal actions per mode" | **State** (or an enum transition table, Topic 06) | vending machine, order lifecycle, elevator door |
| "notify others when X happens" | **Observer** | stock price alerts, auction outbid, cache-invalidation hooks |
| "create the right subtype from input" | **Static factory** | `Vehicle.of(type, plate)`, `RateLimiters.create(algorithm, …)` |
| "many optional parameters, immutable result" | **Builder** | HTTP request, notification message, search query |
| "stack cross-cutting behaviour: auth, logging, rate limit, retry" | **Decorator / chain of responsibility** | API gateway middleware, logger appenders |
| "undo / redo / replay / queue operations" | **Command** | text editor, spreadsheet edits, job scheduler |
| "same skeleton, varying steps" | **Template method**, but usually **composition** instead | report generators, game turn loops |

### Strategy: the most useful one

The problem it solves: one decision point with interchangeable rules, chosen by config or by key.

```java
public interface PricingPolicy {
    Money price(Ticket ticket, Instant exitAt);
}
final class HourlyPricing implements PricingPolicy { … }
final class WeekendFlatPricing implements PricingPolicy { … }
```

Real example in this repo: [`RateLimitAlgorithm<S>`](../21-rate-limiter/src/main/java/com/lld/ratelimiter/RateLimitAlgorithm.java),
with token bucket, sliding log and sliding counter swapped via `RateLimiters.create(...)`. One
contract test runs against all three, which is the testing dividend of the pattern.

**When not to:** there's only one rule and nothing in the prompt suggests a second. A
`java.util.function.Function` or a lambda is often enough for a single-method strategy.

### State

The problem it solves: actions are legal or illegal depending on mode, and each mode behaves
differently.

```java
sealed interface VendingState permits Idle, HasMoney, Dispensing {}
```

There are two shapes: classes per state (GoF State), or an enum plus a transition table.
**Topic 06 compares them.** Default to the enum table in a 45-minute round. Use State classes
only when per-state *behaviour*, not just legality, differs a lot.

**When not to:** fewer than about 3 states with trivial behaviour. A `switch` on an enum is
clearer.

### Observer, with listener isolation

The problem it solves: decouple the event source from the reactions.

```java
public final class EventBus<E> {
    private final List<Consumer<E>> listeners = new CopyOnWriteArrayList<>(); // read-mostly: subscribe rare, publish hot

    public void subscribe(Consumer<E> l) { listeners.add(Objects.requireNonNull(l)); }

    public void publish(E event) {
        for (Consumer<E> l : listeners) {          // iterates a snapshot: subscribe/unsubscribe mid-publish is safe
            try {
                l.accept(event);
            } catch (RuntimeException e) {         // ISOLATION: one bad listener must not starve the rest
                log.warn("listener failed", e);
            }
        }
    }
}
```

The staff points:

- **Isolation.** Without the `try/catch`, listener #2 throwing means listeners #3 to #N never
  run. Old reviews flagged exactly this.
- **Snapshot iteration.** `CopyOnWriteArrayList` is right *here* because subscriptions are rare
  and publishes frequent. It is **not** a blanket concurrency fix: every write copies the whole
  array (see Topic 12).
- **Synchronous by default.** A slow listener blocks the publisher. The follow-up answer is a
  per-listener executor or queue, and then you own ordering, backpressure and error delivery
  (Topic 09).
- **No re-entrancy surprises.** A listener that publishes again recurses. Say whether that's
  allowed.

**When not to:** a single known consumer. Call it directly. Observer hides control flow, and
debugging "who reacted to this?" gets harder.

### Static factory

```java
public static KeyedRateLimiter<?> create(Algorithm a, RateLimit limit, Ticker ticker) {
    return switch (a) {
        case TOKEN_BUCKET           -> new KeyedRateLimiter<>(new TokenBucket(limit), ticker);
        case SLIDING_WINDOW_LOG     -> new KeyedRateLimiter<>(new SlidingWindowLog(limit), ticker);
        case SLIDING_WINDOW_COUNTER -> new KeyedRateLimiter<>(new SlidingWindowCounter(limit), ticker);
    };
}
```

A static factory gives a name, can return a subtype, and the exhaustive `switch` breaks the build
when a new enum constant is added. **Abstract Factory** (a factory *interface* with families of
products) almost never pays off in a 45-minute round.

**When not to:** a plain constructor says it all. `new Money(500, INR)` doesn't need
`Money.create`.

### Builder

Only for **many optional parameters** feeding an **immutable** object, roughly 4 or more
optional fields. For 2–3 required fields, a record constructor is better: the compiler checks it
and a builder can't. A builder must validate in `build()`, otherwise you've moved the invariant
out of the constructor and lost it.

**When not to:** records with all-required fields; anything mutable.

### Decorator / chain of responsibility (middleware)

```java
interface Handler { Response handle(Request r); }

final class RateLimited implements Handler {
    private final Handler next; private final RateLimiter limiter;
    RateLimited(Handler next, RateLimiter limiter) { this.next = next; this.limiter = limiter; }
    public Response handle(Request r) {
        Decision d = limiter.tryAcquire(r.clientId());
        return d.allowed() ? next.handle(r) : Response.tooManyRequests(d.retryAfter());
    }
}
// Handler pipeline = new Authenticated(new RateLimited(new Logged(router), limiter), auth);
```

**Order is semantics.** Put rate limiting *before* expensive auth (a DB lookup) and attackers
can't burn your auth backend, but you can then only limit by IP. Put it after and you can limit
per user. **Say which you chose.** This is the core of capstone 46 (API gateway).

**When not to:** one fixed cross-cutting concern. Inline it.

### Command (undo/redo)

```java
interface EditCommand { void apply(Document d); void undo(Document d); }
// Deque<EditCommand> undo, redo; executing a new command clears redo.
```

The staff points:

- Undo must restore **exactly**, so commands capture what they need (the deleted text, not just
  a range).
- Snapshot (memento) and command-log undo are a memory vs CPU trade-off.
- Coalesce keystrokes so one typed word is one undo step.

**When not to:** no undo, replay or queueing requirement. Then a command object is a method
call in disguise.

### Template method vs composition

Template method (an abstract base class with `final` skeleton and abstract steps) couples
subclasses to the base class's internals and allows only one axis of variation. **Prefer
composition**: inject the varying steps as strategies. Template method is acceptable when the
skeleton is truly fixed and there's only one axis, e.g. a game loop with `init/turn/isOver`.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| **Singleton** for the parking lot / logger / service | Global mutable state. Untestable (can't inject a fake clock or reset between tests) and hides dependencies. Construct one instance and pass it in. "Single instance" is a wiring decision, not a class property |
| Factory + Builder + Strategy on a 3-class problem | Indirection without a change axis. The interviewer spends their time on navigation instead of your logic |
| Strategy interface with one implementation and no hint of a second | Speculative generality; YAGNI |
| Observer without isolation | One throwing listener silently disables the rest |
| `CopyOnWriteArrayList` as "the thread-safe list" | O(n) copy per write. Right only for read-mostly listener lists (Topic 12) |
| Decorators whose order is accidental | Rate limit vs auth vs logging order changes security and cost |
| Visitor for a closed hierarchy in Java 21 | A sealed interface + pattern-matching `switch` does the same with no double dispatch |
| Naming classes after patterns (`PricingStrategyImpl`) | Name the *domain* concept (`WeekendFlatPricing`) |

### "I thought X, but actually Y"

- **"Patterns show seniority."** *Restraint* shows seniority. Name the pattern you chose
  against: "I considered State classes, but an enum table is enough for 4 states."
- **"Java 21 makes patterns obsolete."** Records, sealed types and lambdas *shrink* them
  (Visitor → `switch`, single-method Strategy → lambda, Command → a record + a `switch`). The
  change axis they address still exists.

### When NOT to (summary rule)

Introduce a pattern only when you can point at **the line of the prompt, or the likely
follow-up, that it makes cheap**. If you can't, write the concrete class.

### Interview move

When you introduce an interface, say: *"Pricing is the axis I expect to change, so it's a
Strategy. Everything else stays concrete until a follow-up demands it."* When asked about a
pattern you skipped: *"Singleton would make the clock and the lot untestable, so I construct one
instance in `main` and inject it."*

---

## 4. Official References

- `java.util.function` (lambdas as single-method strategies/commands): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/function/package-summary.html
- `CopyOnWriteArrayList` (snapshot iterators, cost of mutation): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CopyOnWriteArrayList.html
- JEP 409, Sealed Classes: https://openjdk.org/jeps/409
- JEP 441, Pattern Matching for `switch` (the modern Visitor replacement): https://openjdk.org/jeps/441
- Gamma, Helm, Johnson, Vlissides, *Design Patterns: Elements of Reusable Object-Oriented Software*: Strategy, State, Observer, Decorator, Chain of Responsibility, Command, Template Method.
- Joshua Bloch, *Effective Java* (3rd ed.): "Consider static factory methods instead of constructors", "Consider a builder when faced with many constructor parameters", "Favor composition over inheritance".

---

## 5. Key Takeaways

1. A pattern is an answer to a **change axis**. Find the axis in the prompt first.
2. **Strategy** is the workhorse: one interface at the varying policy, with a contract test
   across implementations.
3. **Observer** needs listener **isolation** and a deliberate sync/async choice.
   `CopyOnWriteArrayList` fits listener lists specifically.
4. **Static factory** plus an exhaustive `switch` beats Abstract Factory in a round.
5. **Builder** only for many optional fields, and validate in `build()`.
6. **Decorator order is semantics**: rate-limit/auth/logging order changes cost and security.
7. **Command** when undo/replay exists; it must capture enough to undo exactly.
8. Prefer **composition** over template method. Avoid **Singleton**: inject one instance instead.
9. Name what you **didn't** use and why. That restraint is the staff signal.

---

[← Topic 04: Interface-First API and Errors](../04-interface-first-api-and-errors/) · [Index](../README.md) · [Topic 06: State Machines →](../06-state-machines/)

**Self-grade: ADEQUATE.** Each pattern has its problem, a 45-minute code shape and when-not-to,
plus a prompt → pattern table and real links into the rate-limiter code. But it's docs-only, and
the listener-isolation and decorator-order claims aren't backed by runnable tests in this topic
yet.
