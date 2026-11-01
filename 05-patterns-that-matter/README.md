# Topic 05: The Patterns That Actually Matter (and When Not to Use Them)

Interviewers don't score "used six GoF patterns". (GoF means the "Gang of Four" book of classic design patterns.) They score whether the follow-up was cheap. A candidate who wraps a parking lot in a `SpotFactoryBuilderSingleton` has spent 10 minutes on extra layers that no follow-up needed. A candidate who put **one** interface where pricing varies handles "add weekend pricing" in two minutes. This topic covers the eight patterns that come up in LLD rounds. For each one you get the problem it solves, the shape that fits in 45 minutes, and the case where it is the wrong call. Stuffing in patterns reads as mid-level. Naming the pattern you *didn't* use, and why, reads as staff.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

A design pattern is a named answer to a **change that keeps recurring**. Without one, each change spreads through `if/else` chains. Every new vehicle type, pricing rule or notification channel edits the same five methods, and one gets missed. With too many patterns, the code is all layers, the interviewer can't follow it, and you run out of time before any real logic exists.

What the interviewer probes:

- Can you **spot the axis of change** in the prompt? (This is the one thing that will vary, such as "pricing may vary", "new piece types" or "undo".)
- Do you use a pattern **at that axis only**?
- Can you say **what it costs**: an extra type, one more hop, harder debugging?

---

## 2. How It Solves It

### Prompt → the pattern that genuinely helps

| Prompt signal | Pattern | Where |
|---|---|---|
| "support multiple algorithms / policies / pricing rules" | **Strategy** | rate limiter algorithm, parking pricing, elevator scheduling, order matching rule |
| "behaviour depends on current mode; illegal actions per mode" | **State** (or an enum transition table, Topic 06) | vending machine, order lifecycle, elevator door |
| "notify others when X happens" | **Observer** | stock price alerts, auction outbid, cache-invalidation hooks |
| "create the right subtype from input" | **Static factory** | `Vehicle.of(type, plate)`, `RateLimiterFactory.create(algorithm, …)` |
| "many optional parameters, immutable result" | **Builder** | HTTP request, notification message, search query |
| "stack cross-cutting behaviour: auth, logging, rate limit, retry" | **Decorator / chain of responsibility** | API gateway middleware, logger appenders |
| "undo / redo / replay / queue operations" | **Command** | text editor, spreadsheet edits, job scheduler |
| "same skeleton, varying steps" | **Template method**, but usually **composition** instead | report generators, game turn loops |

### Strategy: the most useful one

The problem it solves: one decision point with swappable rules, chosen by config or by key.

```java
public interface PricingPolicy {
    Money price(Ticket ticket, Instant exitAt);
}
final class HourlyPricing implements PricingPolicy { … }
final class WeekendFlatPricing implements PricingPolicy { … }
```

Real example in this repo: [`RateLimitAlgorithm<S>`](../21-rate-limiter/src/main/java/com/lld/ratelimiter/algorithm/RateLimitAlgorithm.java), with token bucket, sliding log and sliding counter swapped via `RateLimiterFactory.create(...)`. One contract test runs against all three, which is the testing bonus of the pattern.

**When not to:** there is only one rule and nothing in the prompt hints at a second. A `java.util.function.Function` or a lambda is often enough for a strategy with one method.

### State

The problem it solves: which actions are legal depends on the current mode, and each mode behaves differently.

```java
sealed interface VendingState permits Idle, HasMoney, Dispensing {}
```

There are two shapes: a class per state (the GoF State pattern), or an enum plus a transition table. **Topic 06 compares them.** In a 45-minute round, default to the enum table. Use State classes only when the *behaviour* per state, not just what is legal, differs a lot.

**When not to:** fewer than about 3 states with simple behaviour. A `switch` on an enum is clearer.

### Observer, with listener isolation

The problem it solves: keep the thing that raises an event apart from the code that reacts to it.

```java
public final class EventBus<E> {
    private final List<Consumer<E>> listeners = new CopyOnWriteArrayList<>(); // read-mostly: subscribe rare, publish hot

    /** Adds a listener; safe to call while a publish is running. */
    public void subscribe(Consumer<E> l) { listeners.add(Objects.requireNonNull(l)); }

    /** Sends the event to every listener, and keeps going if one of them throws. */
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

Worked example: three listeners A, B, C are subscribed, and B throws. Without the `try/catch`, A runs, B throws, and C never runs. With it, A runs, B's error is logged, and C still runs.

The staff points:

- **Isolation.** Without the `try/catch`, if listener #2 throws, listeners #3 to #N never run. Old reviews flagged exactly this.
- **Snapshot iteration.** A `CopyOnWriteArrayList` copies its array on every write, and readers loop over the old copy. It is right *here* because subscribing is rare and publishing is frequent. It is **not** a blanket fix for thread safety, because every write copies the whole array (see Topic 12).
- **Synchronous by default.** A slow listener blocks the publisher. The follow-up answer is one executor or queue per listener. Then you own ordering, backpressure (slowing senders when receivers can't keep up) and error delivery (Topic 09).
- **Re-entrancy.** A listener that publishes again causes a loop. Say whether that is allowed.

**When not to:** there is a single known consumer. Call it directly. Observer hides the control flow, so "who reacted to this?" gets harder to debug.

### Static factory

A static factory is a static method that builds an object, instead of calling a constructor.

```java
public static KeyedRateLimiter<?> create(Algorithm a, RateLimit limit, Ticker ticker) {
    return switch (a) {
        case TOKEN_BUCKET           -> new KeyedRateLimiter<>(new TokenBucket(limit), ticker);
        case SLIDING_WINDOW_LOG     -> new KeyedRateLimiter<>(new SlidingWindowLog(limit), ticker);
        case SLIDING_WINDOW_COUNTER -> new KeyedRateLimiter<>(new SlidingWindowCounter(limit), ticker);
    };
}
```

A static factory has a name, can return a subtype, and its `switch` covers every case, so adding an enum value breaks the build until you handle it. **Abstract Factory** (a factory *interface* that builds whole families of related objects) almost never pays off in a 45-minute round.

**When not to:** a plain constructor says it all. `new Money(500, INR)` doesn't need `Money.create`.

### Builder

Use it only for **many optional parameters** feeding an **immutable** object, roughly 4 or more optional fields. For 2 or 3 required fields, a record constructor is better, because the compiler checks every field and a builder can't. A builder must validate in `build()`. Otherwise you moved the rule out of the constructor and lost it.

**When not to:** records where every field is required, and anything mutable.

### Decorator / chain of responsibility (middleware)

A decorator wraps another object with the same interface and adds a behaviour. Stack several and you get a chain, where each link either passes the call on or stops it.

```java
interface Handler { Response handle(Request r); }

final class RateLimited implements Handler {
    private final Handler next; private final RateLimiter limiter;
    RateLimited(Handler next, RateLimiter limiter) { this.next = next; this.limiter = limiter; }

    /** Passes the request on if the limiter allows it, otherwise answers "too many requests". */
    public Response handle(Request r) {
        Decision d = limiter.tryAcquire(r.clientId());
        return d.allowed() ? next.handle(r) : Response.tooManyRequests(d.retryAfter());
    }
}
// Handler pipeline = new Authenticated(new RateLimited(new Logged(router), limiter), auth);
```

**Order changes the meaning.** Put rate limiting *before* a costly auth step (a DB lookup) and attackers can't burn your auth backend, but you can then only limit by IP. Put it after, and you can limit per user. **Say which you chose.** This is the core of capstone 46 (API gateway).

**When not to:** there is one fixed cross-cutting concern. Put it inline.

### Command (undo/redo)

A command is an action stored as an object, so you can run it, undo it, queue it or replay it.

```java
interface EditCommand { void apply(Document d); void undo(Document d); }
// Deque<EditCommand> undo, redo; executing a new command clears redo.
```

The staff points:

- Undo must restore things **exactly**, so a command keeps what it needs (the deleted text, not just a range).
- A snapshot (memento) and a command log both support undo. The trade-off is memory against CPU time.
- Merge keystrokes so one typed word is one undo step.

**When not to:** there is no undo, replay or queueing need. Then a command object is just a method call in disguise.

### Template method vs composition

Template method is an abstract base class with a `final` skeleton method and abstract steps. It ties subclasses to the base class's internals, and it allows only one thing to vary. **Prefer composition**: pass in the varying steps as strategies. Template method is fine when the skeleton is truly fixed and only one thing varies, such as a game loop with `init/turn/isOver`.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| **Singleton** for the parking lot, logger or service | It is global mutable state. You can't test it (no fake clock, no reset between tests) and it hides dependencies. Build one instance and pass it in. "Single instance" is a wiring choice, not a class property |
| Factory + Builder + Strategy on a 3-class problem | Extra layers with nothing varying. The interviewer spends time navigating instead of reading your logic |
| A Strategy interface with one implementation and no hint of a second | Building for a future that may never come (YAGNI: "you aren't gonna need it") |
| Observer with no isolation | One throwing listener silently disables the rest |
| `CopyOnWriteArrayList` as "the thread-safe list" | Each write copies the whole array, O(n). Right only for listener lists that are mostly read (Topic 12) |
| Decorators stacked in an accidental order | The order of rate limit, auth and logging changes security and cost |
| Visitor for a closed hierarchy in Java 21 | A sealed interface plus a pattern-matching `switch` does the same with no double dispatch |
| Naming classes after patterns (`PricingStrategyImpl`) | Name the *domain* idea (`WeekendFlatPricing`) |

### "I thought X, but actually Y"

- **"Patterns show seniority."** *Restraint* shows seniority. Name the pattern you chose against: "I considered State classes, but an enum table is enough for 4 states."
- **"Java 21 makes patterns obsolete."** Records, sealed types and lambdas make them *smaller* (Visitor becomes `switch`, a one-method Strategy becomes a lambda, Command becomes a record plus a `switch`). The axis of change they serve still exists.

### When NOT to (summary rule)

Add a pattern only when you can point at **the line of the prompt, or the likely follow-up, that it makes cheap**. If you can't, write the plain class.

### Interview move

When you add an interface, say: *"Pricing is the axis I expect to change, so it's a Strategy. Everything else stays concrete until a follow-up needs more."* When asked about a pattern you skipped, say: *"Singleton would make the clock and the lot untestable, so I build one instance in `main` and inject it."*

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

1. A pattern answers a **change axis**. Find the axis in the prompt first.
2. **Strategy** is the workhorse: one interface at the point where the policy varies, with a contract test across implementations.
3. **Observer** needs listener **isolation** and a deliberate sync/async choice. `CopyOnWriteArrayList` fits listener lists specifically.
4. **Static factory** plus a `switch` that covers every case beats Abstract Factory in a round.
5. Use **Builder** only for many optional fields, and validate in `build()`.
6. **Decorator order is meaning**: the order of rate limit, auth and logging changes cost and security.
7. Use **Command** when undo or replay exists. It must keep enough to undo exactly.
8. Prefer **composition** over template method. Avoid **Singleton**: inject one instance instead.
9. Name what you **didn't** use and why. That restraint is the staff signal.

---

[← Topic 04: Interface-First API and Errors](../04-interface-first-api-and-errors/) · [Index](../README.md) · [Topic 06: State Machines →](../06-state-machines/)

**Self-grade: ADEQUATE.** Each pattern has its problem, a 45-minute code shape and when-not-to, plus a prompt → pattern table and real links into the rate-limiter code. But it's docs-only, and the listener-isolation and decorator-order claims aren't backed by runnable tests in this topic yet.
