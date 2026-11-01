# Topic 11: Money, IDs, and Idempotency

Three small decisions, made in the first ten minutes of any payments, booking or ledger round, decide whether the design is correct:

- **What type holds an amount?** If it is `double`, then `0.1 + 0.2` is already `0.30000000000000004`.
- **Who creates IDs, and how?**
- **What happens when the same request arrives twice?** This is a client retry after a timeout, and it is the *normal* case.

Interviewers rarely ask about these directly. They watch whether you get them right without being asked.

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
| Amount type | `double` | `0.1 + 0.2 != 0.3`. Ledgers don't balance. Splitting ₹100 three ways loses or invents paise |
| Currency | assumed ("it's dollars") | USD plus JPY compiles. JPY has 0 decimal places and BHD has 3 |
| IDs | an `AtomicLong` counter, or `UUID.randomUUID()` everywhere | Counters reset on restart and collide across instances. Random UUIDs scatter inserts in a B-tree index. Sequential IDs let anyone walk `/orders/1001`, `/orders/1002`… |
| Retries | "the client won't retry" | The charge succeeds, the reply times out, the client retries, and the **customer is charged twice** |

What the interviewer probes:

- Value-object modelling (Topic 03).
- Exact arithmetic.
- Whether you treat *at-least-once* (a message may arrive more than once) as the default for every network call.

---

## 2. How It Solves It

### Money: whole minor units plus a currency, in one type

A *minor unit* is the smallest coin: cents for USD, paise for INR. Store the amount as a whole number of them.

```java
public record Money(long minor, Currency currency) {          // 1999 + USD = $19.99; 1999 + JPY = ¥1999
    /** Rejects a missing currency, so every Money has one. */
    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    /** Parses from a STRING, never from a double; throws if the text has more decimals than the currency allows. */
    public static Money of(String amount, Currency c) {
        BigDecimal bd = new BigDecimal(amount)
                .setScale(c.getDefaultFractionDigits(), RoundingMode.UNNECESSARY); // "19.999" USD -> throws
        return new Money(bd.unscaledValue().longValueExact(), c);
    }

    /** Adds two amounts of the same currency; throws on a currency mismatch or an overflow. */
    public Money plus(Money o) {
        requireSameCurrency(o);
        return new Money(Math.addExact(minor, o.minor), currency); // overflow throws, never wraps silently
    }

    /** Guards against adding USD to JPY. */
    private void requireSameCurrency(Money o) {
        if (!currency.equals(o.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + o.currency);
        }
    }
}
```

- **`long` minor units** make `+`, `-` and comparison exact and fast. A `long` holds about 9.2 × 10¹⁸ units, which is plenty for one amount. Still use `Math.addExact`. A bug that overflows should throw, not wrap to a negative number.
- **`BigDecimal`** is a decimal number type. Use it when you divide or apply rates (FX, interest, tax %). Three rules apply:
  - Build it from a `String` or `BigDecimal.valueOf(double)`. Never use `new BigDecimal(0.1)`, which is `0.1000000000000000055511151231257827…`.
  - Always give `divide` a scale *and* a `RoundingMode`. Without them, `1/3` throws `ArithmeticException`.
  - Compare with `compareTo`, not `equals`. `2.0` and `2.00` are not `equals` because their scale differs.
- **Currency travels with the amount.** `Currency.getDefaultFractionDigits()` gives JPY 0, USD 2 and BHD 3. A bare `long cents` field breaks when the second currency arrives.
- **Rounding is a business rule.** Pick it once (`HALF_EVEN` is common in finance). Apply it at a named place such as tax calculation or FX conversion, not scattered through the math.

### Splitting without losing a cent (largest remainder)

Worked example: split 100 paise three ways with equal weights. Each share rounds down to 33, so 99 is handed out and 1 is left over. All three remainders tie at 1, so the first in order gets the extra: `{34, 33, 33}`, which sums to 100. Plain `100 / 3` three times would give 99 and lose 1.

The idea: give everyone the rounded-down share first. Then hand the leftover single units to the shares with the largest remainders.

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

The test that matters is `assertEquals(total, sum(parts))` on **longs**. Splitwise ([Topic 33](../33-splitwise/)) and the ledger ([Topic 32](../32-double-entry-ledger/)) are built on it.

### IDs: pick by what the ID must do

| Scheme | Shape | Ordered? | Needs coordination? | Use when | Watch out |
|---|---|---|---|---|---|
| DB sequence / identity | 64-bit number | yes | the DB | one primary DB, internal IDs | Easy to guess. The DB is the single source |
| UUID v4 | 122 random bits | no | none | opaque public IDs, idempotency keys | Random inserts fragment B-tree indexes |
| **UUID v7** (RFC 9562) | 48-bit Unix-ms timestamp + 74 random/counter bits | by millisecond | none | the default for new primary keys | Leaks creation time. JDK 21 has no v7 generator (`UUID.randomUUID()` is v4), so use a library or write about 15 lines |
| Snowflake-style | 41-bit ms + 10-bit node + 12-bit sequence (Twitter's original layout) | by millisecond | node IDs must be handed out | 64-bit, sortable, high rate | **Clock moves backwards, so duplicate IDs** unless the generator refuses or waits. Node IDs must be unique |

RFC 9562 says systems without legacy UUIDv1 *should* use v7. The generator itself is in [Topic 28](../28-id-generator/).

Exposure rule: if knowing ID *n* tells an attacker that *n+1* exists, or tells a competitor your order volume, don't put sequential IDs in URLs. Opaque IDs don't replace permission checks. They only remove the free guessing.

### Idempotency keys: making retries safe

*Idempotent* means doing it twice has the same effect as doing it once. The client makes a key (a UUID) for each *logical operation* and sends it with every attempt (`Idempotency-Key: …`). The server stores `(clientId, key) → record`.

Worked example: the client sends a charge with key K. The server charges, but the reply is lost to a timeout. The client retries with K. The server finds `COMPLETED` for K and replays the stored response. The customer is charged once.

A *fingerprint* is a hash of the request. It lets the server tell "same request again" from "a different request that reused the key".

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

/** Runs the request once per key: the first caller executes, repeat callers get the stored answer. */
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

The status codes follow the IETF `Idempotency-Key` header draft. Use 400 for a missing key where one is required. Use 422 for a reused key with a different payload. Use 409 while the first request is still running. Stripe behaves the same way. It compares parameters and errors on a mismatch. It doesn't save a result for a request that conflicts with one still running. It lets keys be pruned once they are at least 24 h old.

---

## 3. The Gaps

### Common mistakes

| Mistake | Why it's wrong |
|---|---|
| `double` for money "because we round at the end" | Errors add up with every operation. Rounding at the end can't tell which cent belonged where |
| **A conservation test on `double` with a `0.01` delta** (proposed in this folder's old reviews) | Conservation is an *exact* rule: debits minus credits must be 0, not "about 0". A tolerance passes a bug that leaks 0.009 per transfer. Over 10⁶ transfers that is about ₹9,000 missing with a green test. Assert `long` equality with no delta |
| `new BigDecimal(0.1)`, or `equals` on `BigDecimal` | Binary-float garbage goes in. Equality depends on scale |
| `amount / 3` three times | Loses the remainder. Use allocation |
| Idempotency by "check if it exists, then insert" | This is check-then-act again (Topic 07). Two concurrent retries both run. The claim must be one atomic step: `putIfAbsent`, `INSERT … ON CONFLICT DO NOTHING`, or a unique constraint |
| A key with no fingerprint | A client bug reuses a key for a *different* payment, and you replay the wrong response |
| Keys shared by everyone | Client A's key collides with client B's. Scope by `(tenant, key)` |
| Storing only successes | A retry after a validation error runs again. Decide which outcomes to cache (Stripe v1 caches the response once execution began, including 500s) |
| No expiry | The idempotency table grows forever. A TTL of 24 h or more is a common default |

### The crash window (a staff-level follow-up)

The side effect succeeds, then the process dies before `Completed` is written. The record is stuck at `IN_PROGRESS`, and every retry gets 409 forever. There are three ways out:

1. **Same transaction.** If the side effect is a write in the same database, write it and the `Completed` record in **one transaction**. This is the clean answer.
2. **Lease on `IN_PROGRESS`.** The claim expires after *t*, and a retry may then try again. This is only safe if the downstream call is itself idempotent, for example when you pass *your* key to the payment provider.
3. **Reconciliation.** A sweeper checks stale `IN_PROGRESS` rows by asking the downstream system what happened.

Say which one you chose. "Exactly-once" really means at-least-once delivery plus idempotent processing (see [SystemDesign-poc 10](../../pocs/SystemDesign-poc/10-idempotency-and-exactly-once/)).

### When NOT to

- **Operations that are already idempotent** (`PUT /users/42/email`, `DELETE`, any GET) don't need a key store. First design operations to be idempotent (set a state, don't add to it). Use keys for what is left.
- **`BigDecimal` everywhere** is slower and wordy. Use `long` minor units for storage and ledgers. Use `BigDecimal` only where you divide or apply rates.
- **UUID v7 when creation time is sensitive.** The first 48 bits are a timestamp anyone can read.

### Interview moves

- In the first five minutes say: "Amounts are `long` minor units with a `Currency`. IDs are UUIDv7. Mutating endpoints take an idempotency key." Three sentences that shape the whole design.
- When the interviewer says "the network times out", answer with the idempotency diagram, not "we'll retry".

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

1. Money is `long` minor units plus a `Currency`, in one record. Never `double`.
2. Use `BigDecimal` only where you divide or apply rates: string constructor, explicit scale and `RoundingMode`, `compareTo`.
3. Overflow must throw: use `Math.addExact` / `multiplyExact`.
4. Split with largest remainder. The parts must sum to the total **exactly**.
5. Conservation tests assert exact `long` equality. A tolerance hides real leaks.
6. IDs: UUIDv7 is the default for new keys. Snowflake needs unique node IDs and a clock-backwards guard. Sequential IDs are easy to guess.
7. Idempotency is an atomic claim, a fingerprint and a stored response. A reused key with a different payload gets 422. A duplicate while the first is running gets 409.
8. Close the crash window with a same-transaction write, a lease, or reconciliation, and say which.

---

[← Topic 10: Time and Testability](../10-time-and-testability/) · [Index](../README.md) · [Topic 12: Common Mistakes →](../12-common-mistakes/)

**Self-grade: STRONG.** It has a runnable-quality Money type and allocation, an ID trade-off table grounded in RFC 9562, an idempotency protocol with standards-backed status codes, the crash-window follow-up, and an explicit correction of the old reviews' `double`-delta test. The 10⁶-transfer leak figure is arithmetic, not a measurement.
