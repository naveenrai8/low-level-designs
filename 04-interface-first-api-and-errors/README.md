# Topic 04: Interface-First API and Error Modeling

The interviewer says "design a rate limiter" and the candidate opens a `class TokenBucket` and
starts typing fields. Twenty minutes later there's a correct algorithm, but nobody agreed on what
the caller gets back. A `boolean` can't carry `Retry-After`, a request for 99 permits against a
limit of 5 returns `false` forever, and "Redis is down" is indistinguishable from "you're over
the limit". Writing the **public interface first** (signatures, return types, and *what each
failure looks like*) is the cheapest 5 minutes in the round. It fixes the contract the tests
check, it's where the follow-ups plug in, and it's the clearest seniority signal you can give
before writing any logic.

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
| Caller can't tell *why* something failed, so it retries everything | Every failure is `false`/`null`/`RuntimeException` |
| Tests are written against internals, so refactors break them | No contract to test against |
| Follow-up "support a second algorithm" means rewriting the callers | Callers depend on a concrete class |
| Normal business outcomes (out of stock, over limit) are thrown as exceptions | Control flow through `catch`. That's slow to read and easy to swallow |
| Server bugs surface as 400s | Exception types mapped too broadly (see §3) |

### What the interviewer is probing

- Do you design from the **caller's point of view**? Inputs, outputs, and every outcome the
  caller must handle.
- Can you tell an **expected outcome** (a value) apart from a **caller bug** and an
  **infrastructure failure** (exceptions of different kinds)?
- Do you leave **one deliberate extension point** for the follow-up, rather than five
  speculative ones?

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

This is the actual contract in [`../21-rate-limiter`](../21-rate-limiter/). Four decisions sit in
those lines:

1. **Return a result type, not `boolean`.** `Decision(allowed, remaining, retryAfter)` carries
   what the HTTP layer needs for `429` + `Retry-After`.
2. **A deny isn't an exception.** Being over the limit is the *normal* operation of a limiter.
3. **`permits > limit` *is* an exception.** It can never succeed, so a deny would invite an
   infinite retry loop.
4. **Non-blocking is part of the contract.** A blocking variant (`acquire`) is a different
   method with different failure modes (interruption, timeout), not a flag.

### Step 2: classify every failure (the error taxonomy)

| Kind | Example | Representation | Caller's reaction | HTTP mapping (at the edge) |
|---|---|---|---|---|
| **Expected business outcome** | over limit, seat already held, insufficient funds | **A value**: result record, sealed result type, `Optional`, `enum` status | Branch on it | 409 / 422 / 429 / 200-with-status |
| **Caller bug / validation** | blank key, negative amount, `permits > limit` | **Unchecked**: `IllegalArgumentException`, `NullPointerException` (via `Objects.requireNonNull`) | Fix the code; never retry | 400, translated *at the call site* |
| **Illegal operation for the current state** | `cancel()` on a shipped order | `IllegalStateException` or a domain exception (Topic 06) | Usually a race or a bug, so re-read state | 409 |
| **Transient infrastructure** | Redis timeout, DB unavailable | A domain exception wrapping the cause (`LimiterUnavailableException`), possibly checked | Retry with backoff, or fail open/closed per policy | 503 |
| **Invariant violation (our bug)** | ledger doesn't balance | `AssertionError` / `IllegalStateException`, **never caught** for control flow | Page someone | 500 |

When the outcomes carry different data, use a sealed result type:

```java
public sealed interface HoldResult permits Held, AlreadyHeld, SoldOut {}
public record Held(HoldId id, Instant expiresAt) implements HoldResult {}
public record AlreadyHeld(Instant retryAfter) implements HoldResult {}
public record SoldOut() implements HoldResult {}

switch (booking.hold(seat, user)) {            // compiler forces the caller to handle every outcome
    case Held h        -> confirmLater(h);
    case AlreadyHeld a -> suggestOther(a);
    case SoldOut s     -> showSoldOut();
}
```

### Checked vs unchecked

- **Unchecked** for caller bugs and invariant violations: the caller can't meaningfully
  recover, so a `throws` clause is noise.
- **Checked** only when the caller *can and must* decide (I/O, a remote store), and only where
  lambdas/streams won't be fighting it. In a 45-minute round most candidates go fully
  unchecked, which is fine. **Say why.**
- Never `throws Exception`. Never catch `Exception` to "be safe" in core code.

### `Optional` rules

- Use it as a **return type** for "may be absent" lookups: `Optional<Ticket> find(TicketId)`.
- **Not** as a field, a parameter, or a collection element. **Not** to signal failure reasons
  (it has only one empty).
- Never call `Optional.get()` without a guard. Use `orElseThrow()` so the intent is visible.

### Idempotent operations

A retried request must not double-apply. Design the signature so the caller passes the identity
of the attempt:

```java
Payment charge(IdempotencyKey key, AccountId from, Money amount);  // same key → same Payment, charged once
```

Identical retries return the original result. A **different** request reusing the same key is a
caller bug, so throw. Storage and expiry of keys are covered in Topic 11.

### Designing for the follow-up, without speculative generality

- Put **one interface at the seam the prompt hints will change** (algorithm, pricing, matching
  rule, notification channel). Name it in the round: *"this is where F1 plugs in."*
- Keep everything else concrete and `final`. A `RateLimiterFactoryProvider` with one
  implementation is a negative signal.
- A static factory (`RateLimiters.create(Algorithm, …)`) is enough to hide the concrete types.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `boolean tryX()` | Loses *why* and *when*, so callers retry-storm |
| `null` for "not found" | Every caller has to remember; the NPE surfaces far from the cause |
| Exceptions for expected outcomes (`RateLimitExceededException` on every deny) | Stack-trace cost on the hot path, plus control flow by `catch`. Fine *at the HTTP edge* as a translation, not in the core |
| Returning `false` for impossible requests | Infinite retry loops |
| A catch-all `catch (Exception e) { log; return false; }` | Turns bugs into silent denials |
| **A global `@ExceptionHandler(IllegalArgumentException.class)` → 400** | The JDK and Spring throw IAE for *their own* faults. In `../app` a missing `-parameters` flag became a wall of 400s that looked like caller errors. Translate at the call site instead. See [SpringBoot-poc 33a, gap (e)](../../pocs/SpringBoot-poc/33a-problemdetail-rfc9457-errors/) |
| Error messages leaking internals | The message becomes public API once it reaches a response body |
| Interface with 12 methods "for later" | Every method is a promise. Unused ones are untested promises |

### "I thought X, but actually Y"

- **"Exceptions are slow, so never use them."** The cost is mostly stack-trace capture, which
  matters on hot paths with high failure rates (a limiter denying 50% of traffic). For rare
  caller bugs it's irrelevant. Choose by **semantics first**.
- **"A sealed result type is overkill."** It's three records. It's worth it the moment
  outcomes carry different data, and the compiler then enforces handling.
- **"`Optional` replaces exceptions."** It replaces `null`. It can't say *why*.

### When NOT to

- **Don't build a result type for a two-outcome call with no payload.** `boolean
  tryLock()` is fine, because the JDK does exactly that.
- **Don't invent a custom exception hierarchy in 45 minutes.** The JDK's `IllegalArgumentException`
  / `IllegalStateException` / `UnsupportedOperationException` cover core-code needs. Add domain
  types only where callers branch on them.
- **Don't design the HTTP API first.** Status codes are an *edge translation* of the core
  taxonomy. Design the core contract, then map it.

### Interview move

Before coding, write the interface on the board and say: *"Over-limit is a normal outcome, so
it's a value with `retryAfter`. Asking for more than the limit is a caller bug, so it throws.
Store unavailability is a separate failure we'll decide fail-open or fail-closed on later. And
this interface is where a second algorithm plugs in."* That's the taxonomy, the contract and the
extension point in 20 seconds.

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

1. Write the **interface + Javadoc first**. It's the contract your tests check and the place
   the follow-up plugs in.
2. **Expected outcome → a value. Caller bug → an unchecked exception.** Infra failure → a
   distinct, wrapped exception.
3. Use a **sealed result type** when outcomes carry different data. The compiler forces callers
   to handle each one.
4. A request that **can never succeed** must throw, never deny.
5. `Optional` is for "may be absent" **return values** only.
6. Make retried operations **idempotent** by putting the attempt's key in the signature.
7. Map to HTTP **at the edge, per call site**. Never map JDK exception types globally.
8. **One** deliberate extension point beats five speculative ones.

---

[← Topic 03: Domain Modeling](../03-domain-modeling/) · [Index](../README.md) · [Topic 05: Patterns That Matter →](../05-patterns-that-matter/)

**Self-grade: STRONG.** It has the full spine, a five-way error taxonomy with representation,
caller reaction and HTTP mapping, a real failure from this repo (the global IAE → 400 masking the
`-parameters` bug), when-not-to, and an interview script. The exception-cost point is stated
qualitatively, not measured.
