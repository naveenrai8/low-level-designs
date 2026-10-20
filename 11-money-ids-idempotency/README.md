# Topic 11: Money, IDs, and Idempotency

Three small decisions, made in the first ten minutes of any payments-, booking- or
ledger-shaped round, quietly decide whether the design is correct:

- **What type holds an amount?** `double`, and `0.1 + 0.2` is already `0.30000000000000004`.
- **Who mints IDs, and how?**
- **What happens when the same request arrives twice?** That's the client retry after a
  timeout, which is the *normal* case.

Interviewers rarely ask about these directly. They watch whether you get them right without being
asked.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

| Decision | Wrong default | What breaks |
|---|---|---|
| Amount type | `double` | `0.1 + 0.2 != 0.3`; ledgers that don't balance; splitting ₹100 three ways loses or invents paise |
| Currency | implied ("it's dollars") | adding USD to JPY compiles; JPY has 0 decimal places, BHD has 3 |
| IDs | `AtomicLong` counter, or `UUID.randomUUID()` everywhere | counters reset on restart and collide across instances; random UUIDs scatter B-tree inserts; sequential IDs let anyone enumerate `/orders/1001`, `/orders/1002`… |
| Retries | "the client won't retry" | a timeout after the charge succeeded → retry → **customer charged twice** |

What's probed: value-object modelling (Topic 03), exact arithmetic, and whether you treat
*at-least-once* as the default delivery guarantee of every network call.

---

## 2. How It Solves It

### Money: integer minor units + currency, in one value type

```java
public record Money(long minor, Currency currency) {          // 1999 + USD = $19.99; 1999 + JPY = ¥1999
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String amount, Currency c) {         // parse from STRING, never from double
        BigDecimal bd = new BigDecimal(amount)
                .setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY); // "19.999" USD -> throws
        return new Money(bd.unscaledValue().longValueExact(), c);
    }

    public Money plus(Money o) {
        requireSameCurrency(o);
        return new Money(Math.addExact(minor, o.minor), currency); // overflow throws, never wraps silently
    }

    private void requireSameCurrency(Money o) {
        if (!currency.equals(o.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + o.currency);
        }
    }
}
```

- **`long` minor units** make `+`, `-` and comparison exact and fast. A `long` holds ~9.2 × 10¹⁸
  minor units, which is plenty for any single amount. Still use `Math.addExact`, because a bug
  that overflows should throw, not wrap negative.
- **`BigDecimal`** is right when you need division or rates (FX, interest, tax %). Then three
  rules apply:
  - Construct from `String` or `BigDecimal.valueOf(double)`, never `new BigDecimal(0.1)`.
    That one is `0.1000000000000000055511151231257827…`.
  - Always pass a scale *and* a `RoundingMode` to `divide`. `1/3` without them throws
    `ArithmeticException`.
  - Compare with `compareTo`, not `equals`: `2.0` and `2.00` are not `equals`, because scale
    differs.
- **Currency travels with the amount.** `Currency.getDefaultFractionDigits()` gives JPY 0,
  USD 2 and BHD 3. A bare `long cents` field is a bug waiting for the second currency.
- **Rounding is a business rule.** Choose it once (`HALF_EVEN` is common in finance) and apply
  it at a named boundary (tax calculation, FX conversion), not scattered through arithmetic.

### Splitting without losing a cent (largest remainder)

```java
/** Splits total by weights; the parts sum to EXACTLY total. */
static long[] allocate(long total, long[] weights) {
    long weightSum = Arrays.stream(weights).sum();
    long[] parts = new long[weights.length];
    long[] remainders = new long[weights.length];
    long allocated = 0;
    for (int i = 0; i < weights.length; i++) {
        parts[i] = Math.multiplyExact(total, weights[i]) / weightSum;    // floor share
        remainders[i] = Math.multiplyExact(total, weights[i]) % weightSum;
        allocated += parts[i];
    }
    Integer[] byRemainder = IntStream.range(0, weights.length).boxed()
            .sorted((a, b) -> Long.compare(remainders[b], remainders[a])) // largest remainder first (stable)
            .toArray(Integer[]::new);
    for (int k = 0; k < total - allocated; k++) {                         // hand out leftover 1-unit pieces
        parts[byRemainder[k]]++;
    }
    return parts;
}
// allocate(10_000, {1,1,1}) -> {3334, 3333, 3333}: ₹100.00 three ways, sums to 10_000 exactly
```

The test that matters is `assertEquals(total, sum(parts))` on **longs**. Splitwise
([Topic 33](../33-splitwise/)) and the ledger ([Topic 32](../32-double-entry-ledger/)) are built
on it.

### IDs: pick by what the ID must do

| Scheme | Shape | Ordered? | Coordination | Use when | Watch out |
|---|---|---|---|---|---|
| DB sequence / identity | 64-bit int | yes | the DB | single primary DB, internal IDs | enumerable; one DB is the minter |
| UUID v4 | 122 random bits | no | none | opaque public IDs, idempotency keys | random inserts fragment B-tree indexes |
| **UUID v7** (RFC 9562) | 48-bit Unix-ms timestamp + 74 random/counter bits | by ms | none | default for new primary keys | leaks creation time; JDK 21 has no v7 generator (`UUID.randomUUID()` is v4), so use a library or ~15 lines |
| Snowflake-style | 41-bit ms + 10-bit node + 12-bit seq (Twitter's original layout) | by ms | node-ID assignment | 64-bit, sortable, high rate | **clock moving backwards ⇒ duplicate IDs** unless the generator refuses/waits; node IDs must be unique |

RFC 9562 says systems without legacy UUIDv1 *should* use v7. The generator itself is
[Topic 28](../28-id-generator/).

Exposure rule: if knowing ID *n* tells an attacker that *n+1* exists, or tells a competitor
your order volume, don't put sequential IDs in URLs. Opaque IDs don't replace authorization
checks, though. They only remove the free enumeration.

### Idempotency keys: making retries safe

The client generates a key per *logical operation* (a UUID) and sends it with every attempt
(`Idempotency-Key: …`). The server keeps `(clientId, key) → record`:

```
          first request                            retry (same key)
client ──POST + key──> server                client ──POST + key──> server
                        │ putIfAbsent(key, IN_PROGRESS(fingerprint))   │ lookup(key)
                        │   absent -> we own it                        │  COMPLETED, same fingerprint -> replay stored response
                        │ do the side effect (charge)                  │  COMPLETED, different fingerprint -> 422
                        │ record = COMPLETED(fingerprint, response)    │  IN_PROGRESS -> 409 (retry later)
client <──response──────┘
```

```java
sealed interface IdemRecord permits InProgress, Completed {}
record InProgress(String fingerprint) implements IdemRecord {}
record Completed(String fingerprint, Response response) implements IdemRecord {}

Response handle(String key, Request req) {
    String fp = fingerprint(req);                         // hash(method, path, canonical body)
    IdemRecord existing = store.putIfAbsent(key, new InProgress(fp));  // ATOMIC claim: only one winner
    if (existing == null) {
        Response r = doTheSideEffect(req);
        store.put(key, new Completed(fp, r));
        return r;
    }
    if (!existing.fingerprint().equals(fp)) return Response.status(422);  // same key, different request
    return switch (existing) {
        case Completed c  -> c.response();                // replay; do NOT re-execute
        case InProgress p -> Response.status(409);        // concurrent duplicate; client retries later
    };
}
```

The status codes follow the IETF `Idempotency-Key` header draft: 400 for a missing key where
one is required, 422 for a reused key with a different payload, and 409 while the original is
still processing. Stripe behaves the same way: it compares parameters and errors on mismatch,
doesn't save a result for a request that conflicts with a concurrent one, and lets keys be pruned
once they're at least 24 h old.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `double` for money "because we round at the end" | Errors compound per operation, and rounding at the end can't recover which cent belonged where |
| **A conservation test on `double` with a `0.01` delta** (proposed in this folder's old reviews) | Conservation is an *exact* invariant: debits − credits must be 0, not "≈ 0". A tolerance passes a bug that leaks 0.009 per transfer, and over 10⁶ transfers that's ~₹9,000 missing with a green test. Assert `long` equality, with no delta |
| `new BigDecimal(0.1)` / `equals` on `BigDecimal` | Binary-float garbage in; scale-sensitive equality |
| `amount / 3` three times | Loses the remainder. Use allocation |
| Idempotency via "check if exists, then insert" | Check-then-act again (Topic 07). Two concurrent retries both execute. The claim must be one atomic `putIfAbsent` / `INSERT … ON CONFLICT DO NOTHING` / unique constraint |
| Key without a fingerprint | A client bug reuses a key for a *different* payment, and you replay the wrong response |
| Keys scoped globally | Client A's key collides with client B's. Scope by `(tenant, key)` |
| Storing only success | A retry after a validation error re-executes. Decide which outcomes are cached (Stripe v1 caches the response once execution began, including 500s) |
| No expiry | The idempotency table grows forever. A TTL of 24 h+ is a common default |

### The crash window (a staff-level follow-up)

The side effect succeeds, then the process dies before `Completed` is written. The record is
stuck `IN_PROGRESS`, and every retry gets 409 forever. Options:

1. **Same transaction.** If the side effect is a DB write in the same database, write it and the
   `Completed` record in **one transaction**. This is the clean answer.
2. **Lease on `IN_PROGRESS`.** It expires after *t*, and a retry may then re-attempt. This is
   only safe if the downstream call is itself idempotent, e.g. you pass *your* key to the payment
   provider.
3. **Reconciliation.** A sweeper resolves stale `IN_PROGRESS` rows by querying the downstream
   system.

Say which one you chose. "Exactly-once" is really at-least-once delivery + idempotent processing
(see [SystemDesign-poc 10](../../pocs/SystemDesign-poc/10-idempotency-and-exactly-once/)).

### When NOT to

- **Naturally idempotent operations** (`PUT /users/42/email`, `DELETE`, any GET) don't need a key
  store. Design operations to be idempotent first (set state, don't increment it). Use keys for
  what's left.
- **BigDecimal everywhere** is slower and verbose. Use `long` minor units for storage and
  ledgers, and `BigDecimal` only where you divide or apply rates.
- **UUID v7 when creation time is sensitive:** the first 48 bits are a timestamp anyone can read.

### Interview moves

- In the first five minutes: "Amounts are `long` minor units with a `Currency`. IDs are UUIDv7.
  Mutating endpoints take an idempotency key." Three sentences, which then shape the whole design.
- When the interviewer says "the network times out", answer with the idempotency diagram, not
  with "we'll retry".

---

## 4. Official References

- `BigDecimal` (scale, `RoundingMode`, `compareTo` vs `equals`): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html
- `Currency.getDefaultFractionDigits`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Currency.html
- `Math.addExact` / `multiplyExact`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html
- RFC 9562, UUIDs (v7 layout, "SHOULD use UUIDv7" guidance): https://www.rfc-editor.org/rfc/rfc9562
- IETF draft, The Idempotency-Key HTTP Header Field (400/422/409 guidance): https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
- Stripe, Idempotent requests: https://docs.stripe.com/api/idempotent_requests
- Martin Fowler, *Patterns of Enterprise Application Architecture*, "Money" pattern (allocation): https://martinfowler.com/eaaCatalog/money.html

---

## 5. Key Takeaways

1. Money = `long` minor units + `Currency` in one record. Never `double`.
2. `BigDecimal` only where you divide or apply rates: string constructor, explicit scale and
   `RoundingMode`, `compareTo`.
3. Overflow must throw: `Math.addExact` / `multiplyExact`.
4. Split with largest remainder. The parts must sum to the total **exactly**.
5. Conservation tests assert exact `long` equality. A tolerance hides real leaks.
6. IDs: UUIDv7 is the default for new keys. Snowflake needs unique node IDs and a
   clock-backwards guard. Sequential IDs are enumerable.
7. Idempotency = atomic claim + fingerprint + stored response. Reused key with a different
   payload → 422; in-flight duplicate → 409.
8. Close the crash window with a same-transaction write, a lease, or reconciliation, and say
   which.

---

[← Topic 10: Time and Testability](../10-time-and-testability/) · [Index](../README.md) · [Topic 12: Common Mistakes →](../12-common-mistakes/)

**Self-grade: STRONG.** It has a runnable-quality Money type and allocation, an ID trade-off table
grounded in RFC 9562, an idempotency protocol with standards-backed status codes, the crash-window
follow-up, and an explicit correction of the old reviews' `double`-delta test. The 10⁶-transfer
leak figure is arithmetic, not a measurement.
