# Topic 04: Interface-First API and Error Modeling

The interviewer says "design a rate limiter". The candidate opens `class TokenBucket` and starts typing fields. Twenty minutes later the algorithm is correct, but nobody agreed on what the caller gets back. A `boolean` cannot carry a `Retry-After` time. A request for 99 permits against a limit of 5 returns `false` forever. And "Redis is down" looks the same as "you are over the limit". The fix is to write the **public interface first**: the method signatures, the return types, and *what each failure looks like*. These are the cheapest 5 minutes in the round. They fix the contract your tests check. They show where follow-ups plug in. And they are the clearest sign of seniority you can give before writing any logic.

**Words used here.** An *interface* is the list of methods a caller can use. An *expected outcome* is a normal result, such as "over the limit". A *caller bug* is a call that is wrong no matter what, such as a blank key. *Idempotent* means doing it twice has the same effect as doing it once. A *contract* is what the interface promises, including how it fails.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

### What breaks when the implementation comes first

| Symptom | Cause |
|---|---|
| The caller cannot tell *why* it failed, so it retries everything | Every failure is `false`, `null` or a bare `RuntimeException` |
| Tests use the internals, so every refactor breaks them | There is no contract to test against |
| "Support a second algorithm" means rewriting the callers | Callers depend on one concrete class |
| Normal outcomes (out of stock, over limit) are thrown as exceptions | Program flow runs through `catch`, which is hard to read and easy to swallow |
| Server bugs show up as 400 errors | Exception types are mapped too broadly (see §3) |

### What the interviewer is probing

- Do you design from the **caller's point of view**? That means inputs, outputs, and every outcome the caller must handle.
- Can you tell an **expected outcome** (a returned value) from a **caller bug** and from an **infrastructure failure** (each an exception of a different kind)?
- Do you leave **one planned extension point** for the follow-up, rather than five guesses?

---

## 2. How It Solves It

### Step 1: write the interface and its Javadoc before any class

```java
public interface RateLimiter {
    default Decision tryAcquire(String key) { return tryAcquire(key, 1); }

    /**
     * All-or-nothing; never blocks.
     * @return allowed, or denied with remaining + retryAfter   (expected outcome → a VALUE)
     * @throws IllegalArgumentException blank key, permits < 1, permits > limit  (caller bug → EXCEPTION)
     */
    Decision tryAcquire(String key, int permits);
}
```

This is the real contract in [`../21-rate-limiter`](../21-rate-limiter/). Four decisions sit in those lines:

1. **Return a result object, not `boolean`.** `Decision(allowed, remaining, retryAfter)` holds what the HTTP layer needs for a `429` response with a `Retry-After` header.
2. **A deny is not an exception.** Being over the limit is the *normal* job of a limiter.
3. **`permits > limit` *is* an exception.** It can never succeed. A deny would invite the caller to retry forever.
4. **Never blocking is part of the contract.** A blocking version (`acquire`) is a different method with different failures (interrupted, timed out). It is not a flag on this one.

Example timeline with a limit of 5 permits: `tryAcquire("alice", 1)` gives `allowed`. After five calls the sixth gives `denied, retryAfter=2s`, which is a value. `tryAcquire("alice", 99)` throws `IllegalArgumentException`, because no amount of waiting makes 99 fit in 5.

### Step 2: sort every failure into a kind

| Kind | Example | Representation | Caller's reaction | HTTP mapping (at the edge) |
|---|---|---|---|---|
| **Expected outcome** | over limit, seat already held, not enough money | **A value**: a result record, sealed result, `Optional` or `enum` | Branch on it | 409 / 422 / 429 / 200 with a status |
| **Caller bug** | blank key, negative amount, `permits > limit` | **Unchecked exception**: `IllegalArgumentException`, or `NullPointerException` via `Objects.requireNonNull` | Fix the code; never retry | 400, set *at the call site* |
| **Wrong for the current state** | `cancel()` on a shipped order | `IllegalStateException` or your own exception (Topic 06) | Usually a race or a bug, so read the state again | 409 |
| **Temporary infrastructure failure** | Redis timeout, database down | Your own exception that wraps the cause (`LimiterUnavailableException`), maybe checked | Retry after a growing delay, or let traffic through or block it, by policy | 503 |
| **Broken rule (our bug)** | the ledger does not balance | `AssertionError` or `IllegalStateException`, **never caught** to steer the program | Alert someone | 500 |

When outcomes carry different data, use a sealed result type. *Sealed* means only the listed result types are allowed:

```java
public sealed interface HoldResult permits Held, AlreadyHeld, SoldOut {}
public record Held(HoldId id, Instant expiresAt) implements HoldResult {}
public record AlreadyHeld(Instant retryAfter) implements HoldResult {}
public record SoldOut() implements HoldResult {}

switch (booking.hold(seat, user)) {            // the compiler forces the caller to handle every outcome
    case Held h        -> confirmLater(h);
    case AlreadyHeld a -> suggestOther(a);
    case SoldOut s     -> showSoldOut();
}
```

### Checked vs unchecked

- Use **unchecked** exceptions (no `throws` needed) for caller bugs and broken rules. The caller cannot recover, so a `throws` clause is just noise.
- Use **checked** exceptions (the compiler forces a catch) only when the caller *can and must* decide what to do, such as for I/O or a remote store. Avoid them where lambdas and streams get in the way. In a 45-minute round most candidates use only unchecked, which is fine. **Say why.**
- Never write `throws Exception`. Never catch `Exception` "to be safe" in core code.

### `Optional` rules

- Use it as a **return type** when a result may be missing: `Optional<Ticket> find(TicketId)`.
- **Not** as a field, a parameter or a list element. **Not** to explain a failure, because it has only one "empty".
- Never call `Optional.get()` without checking first. Use `orElseThrow()` so the intent is clear.

### Idempotent operations

A retried request must not be applied twice. Make the caller pass a key that identifies the attempt:

```java
Payment charge(IdempotencyKey key, AccountId from, Money amount);  // same key → same Payment, charged once
```

Example: a client charges 500, the network drops, and it retries with the same key. It gets the first payment back and is charged once. A **different** request that reuses the key is a caller bug, so throw. Topic 11 covers how keys are stored and expire.

### Designing for the follow-up without building for guesses

- Put **one interface where the prompt hints something will change** (algorithm, pricing, matching rule, notification channel). Say it aloud: *"this is where the first follow-up plugs in."*
- Keep everything else a plain `final` class. A `RateLimiterFactoryProvider` with one implementation counts against you.
- A static factory (`RateLimiterFactory.create(…)`) is enough to hide the concrete classes.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `boolean tryX()` | Loses *why* and *when*, so callers retry in a flood |
| `null` for "not found" | Every caller must remember to check; the NullPointerException appears far from the cause |
| Exceptions for expected outcomes (`RateLimitExceededException` on every deny) | Building a stack trace is slow on a busy path, and `catch` becomes program flow. Fine *at the HTTP edge* as a translation, not in the core |
| Returning `false` for impossible requests | The caller retries forever |
| A catch-all `catch (Exception e) { log; return false; }` | Turns bugs into quiet denials |
| **A global `@ExceptionHandler(IllegalArgumentException.class)` → 400** | The JDK and Spring throw IAE for *their own* faults. In `../app` a missing `-parameters` flag became a wall of 400s that looked like caller errors. Translate at the call site instead. See [SpringBoot-poc 33a, gap (e)](../../pocs/SpringBoot-poc/33a-problemdetail-rfc9457-errors/) |
| Error messages that leak internals | Once a message reaches a response body, it is public API |
| Interface with 12 methods "for later" | Every method is a promise, and unused ones are untested promises |

### "I thought X, but actually Y"

- **"Exceptions are slow, so never use them."** The cost is mostly building the stack trace. That matters on busy paths that fail often, such as a limiter denying 50% of traffic. For rare caller bugs it does not matter. Choose by **meaning first**.
- **"A sealed result type is overkill."** It is three small records. It pays off as soon as outcomes carry different data, and the compiler then makes callers handle each one.
- **"`Optional` replaces exceptions."** It replaces `null`. It cannot say *why*.

### When NOT to

- **Don't build a result type for a call with two outcomes and no extra data.** `boolean tryLock()` is fine, because the JDK does exactly that.
- **Don't invent a tree of custom exceptions in 45 minutes.** The JDK's `IllegalArgumentException`, `IllegalStateException` and `UnsupportedOperationException` cover core code. Add your own types only where callers act differently on them.
- **Don't design the HTTP API first.** Status codes are a *translation at the edge* of the failure kinds above. Design the core contract, then map it.

### Interview move

Before coding, write the interface on the board and say: *"Over the limit is a normal outcome, so it is a value with `retryAfter`. Asking for more than the limit is a caller bug, so it throws. The store being down is a separate failure, and we decide later whether to let traffic through or block it. And this interface is where a second algorithm plugs in."* That covers the failure kinds, the contract and the extension point in 20 seconds.

---

## 4. Official References

- `Optional` (API note: intended for return types): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html
- `IllegalArgumentException`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/IllegalArgumentException.html
- `IllegalStateException`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/IllegalStateException.html
- `Objects.requireNonNull`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Objects.html#requireNonNull(T,java.lang.String)
- JEP 409, Sealed Classes (for result types): https://openjdk.org/jeps/409
- JEP 441, Pattern Matching for `switch`: https://openjdk.org/jeps/441
- RFC 9457, Problem Details for HTTP APIs (the edge representation): https://www.rfc-editor.org/rfc/rfc9457
- Joshua Bloch, *Effective Java* (3rd ed.): "Use exceptions only for exceptional conditions", "Use checked exceptions for recoverable conditions and runtime exceptions for programming errors", "Return optionals judiciously".

---

## 5. Key Takeaways

1. Write the **interface and its Javadoc first**. It is the contract your tests check and the place the follow-up plugs in.
2. **Expected outcome: return a value. Caller bug: throw an unchecked exception.** Infrastructure failure: throw a separate exception that wraps the cause.
3. Use a **sealed result type** when outcomes carry different data. The compiler makes callers handle each one.
4. A request that **can never succeed** must throw, not deny.
5. `Optional` is only for **return values** that may be missing.
6. Make retried operations **idempotent** by putting the attempt's key in the method signature.
7. Map to HTTP **at the edge, at each call site**. Never map JDK exception types globally.
8. **One** planned extension point beats five guesses.

---

[← Topic 03: Domain Modeling](../03-domain-modeling/) · [Index](../README.md) · [Topic 05: Patterns That Matter →](../05-patterns-that-matter/)

**Self-grade: STRONG.** It has the full spine, a five-way error taxonomy with representation, caller reaction and HTTP mapping, a real failure from this repo (the global IAE → 400 masking the `-parameters` bug), when-not-to, and an interview script. The exception-cost point is stated qualitatively, not measured.
