# Topic 21: Rate Limiter

"Design a rate limiter" sounds like a 10-minute question: count requests, and say no after N. The interviewer is waiting for the three places where that simple answer breaks:

1. Two callers race past the check at the same moment, and both get in.
2. A "per minute" window lets 2× through at the minute boundary.
3. Memory grows with every new key an attacker invents.

A staff answer names all three before being asked. It codes one algorithm correctly under concurrency, and it can say what changes when the service runs on 50 instances behind a load balancer.

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
| **Rate limiter** | A gatekeeper that says "yes" or "not now" to each request, so one caller can't send too many too fast. |
| **Key** | Who the limit applies to: a user id, an API key, an IP address. Each key has its own separate limit. |
| **Permit** | Permission for one request. A big request (a batch) can ask for several permits at once. |
| **Limit / window** | "N permits per window W", e.g. 5 per second. The window is the time span the limit is measured over. |
| **Token / bucket** | In the token-bucket algorithm, each key has a bucket of tokens. A request spends tokens. Tokens drip back in over time. |
| **429** | The HTTP status code for "Too Many Requests". |
| **Retry-After** | An HTTP header that tells the client how many seconds to wait before trying again. |
| **Idle key** | A key whose state looks exactly like a brand-new key's. Deleting it changes nothing. |
| **Lock** | A way to make sure only one thread changes a piece of data at a time. |
| **Atomic** | "All in one step." No other thread can see or change the data halfway through. |

---

## 1. The Problem

### What breaks without one

One client sends 10,000 requests a second. It might be a buggy retry loop, a scraper, or a bot trying stolen passwords. Your service has no idea of "too much from one caller", so that client takes every thread and every connection. Every other client times out.

A rate limiter turns that overload into a **429 for that one caller** instead of an outage for everyone. It's the cheapest fairness tool there is.

### What the interviewer is really probing

| Signal | What they want to see |
|---|---|
| Concurrency correctness | "Check and consume" is **atomic per key**, with no global lock |
| Algorithm judgement | You know at least 2 algorithms, their memory and accuracy costs, and **when not to use** each |
| API design | The caller learns *why*, *how many are left*, and *when to retry*, not just a `boolean` |
| Testability | Time is injected. Tests are deterministic and never call `sleep` |
| Resource bounds | Per-key state gets cleaned up without changing any decision |
| Distributed seam | You know an in-process limiter allows K × limit behind K instances, and what to do about it |

### v1 requirements (say these out loud)

1. `tryAcquire(key)` answers allow or deny **immediately**. It never blocks.
2. The limit is "N permits per window W", the same for every key. Different limits per tier come in F1.
3. Keys are independent. Alice using up her limit never affects Bob.
4. A denied caller is told `retryAfter` (how long to wait) and `remaining` (how many permits are left).
5. It's thread-safe, even when many threads hit one hot key.
6. Memory stays bounded as keys come and go.

### Clarifying questions (with the answer I assume if the interviewer shrugs)

| Question | Assumed answer | Why it matters |
|---|---|---|
| In-process, or shared across instances? | In-process for v1; distributed is F3 | Decides whether you write a class or a Redis script |
| Reject, or queue (wait until allowed)? | Reject | Queueing is traffic *shaping* (leaky bucket), a different component |
| Are bursts OK? | Yes, up to N | Picks token bucket over sliding log |
| What's the key: user, API key, IP, (user, endpoint)? | Any string the caller builds | Keeps the limiter generic |
| Can one request cost more than 1 (a batch, LLM tokens)? | Yes, `permits ≥ 1`, all-or-nothing | Changes the API and the deny math |
| Exact or approximate? | Approximate is fine at high limits; exact for login/OTP | Picks the algorithm |
| What if the limiter itself fails? | Fail **open** (let traffic through) for general APIs, fail **closed** (block) for security limits | A product decision, so ask |

### Explicitly out of scope for v1

Request queueing/shaping, per-tier config, distributed enforcement, HTTP wiring (that lives in [`app/`](../app/)), and keeping state across restarts.

---

## 2. How It Solves It

### Entities and invariants

An *invariant* is a rule that is always true for that type.

| Type | Kind | Invariant |
|---|---|---|
| `RateLimit(permits, window)` | value (record) | `permits ≥ 1`, `window > 0`, and `window ≥ permits` nanoseconds (so one token takes at least 1 ns) |
| `Decision(allowed, remaining, retryAfter)` | value (record) | `remaining ≥ 0`. Allowed means `retryAfter == 0`. Denied means `retryAfter > 0` |
| `RateLimitAlgorithm<S>` | strategy | Pure math over a per-key state `S`. **Not** thread-safe, on purpose |
| `KeyedRateLimiter<S>` | the component | For one key, "read the time → check → consume" happens atomically |
| `Ticker` | port | A clock that only moves forward, in nanoseconds. Production uses `System::nanoTime`; tests use a fake one |

The key design choice: each algorithm is **simple single-threaded math**, and `KeyedRateLimiter` handles *all* the concurrency. So the concurrency is written and tested once, and each algorithm is tested with plain, one-call-after-another tests.

### Public interface first

```java
public interface RateLimiter {
    default Decision tryAcquire(String key) { return tryAcquire(key, 1); }

    /** All-or-nothing. Throws IllegalArgumentException if key is blank, permits < 1, or permits > limit (it could never succeed — a caller bug, not a 429). */
    Decision tryAcquire(String key, int permits);
}

public record Decision(boolean allowed, long remaining, Duration retryAfter) { ... }
```

Why does `permits > limit` throw instead of returning "denied"? A deny means "retry later". That request can never succeed, so a 429 would make the client retry forever.

### Class diagram (whiteboard version)

Formal UML (class diagrams for core and `app/`, plus request and eviction sequences): [`uml.md`](uml.md).

```
            <<interface>>
             RateLimiter
     tryAcquire(key, permits): Decision
                  ^
                  | implements
     KeyedRateLimiter<S> ------------------> Ticker  (System::nanoTime | FakeTicker)
     - states: ConcurrentHashMap<String,S>
     + evictIdle(): int
                  |
                  | delegates math to (Strategy)
                  v
        <<interface>> RateLimitAlgorithm<S>
        newState(now) / tryAcquire(s, now, n) / isIdle(s, now)
           ^                 ^                     ^
     TokenBucket      SlidingWindowLog     SlidingWindowCounter
     S = {tokens,     S = ArrayDeque<Long>  S = {windowStart,
          lastRefill}     of grant times         current, previous}
```

### Sequence: one `tryAcquire`

```
caller            KeyedRateLimiter          CHM bin lock(key)          algorithm
  | tryAcquire(k,n)     |                          |                       |
  |-------------------->| validate(k, n)           |                       |
  |                     | states.compute(k, fn) -->| lock bin              |
  |                     |                          | now = ticker.read()   |  <- time read INSIDE lock
  |                     |                          | s = existing ?: newState(now)
  |                     |                          | d = tryAcquire(s, now, n) -->| refill/expire,
  |                     |                          |                       |  check, consume
  |                     |                          | return s (stays mapped)      |
  |                     |<-- unlock bin -----------|                       |
  |<-- Decision d ------|                          |                       |
```

### The algorithms, each with a tiny example first

#### Token bucket (the default)

**Idea:** each key has a bucket that holds up to `capacity` tokens. A request takes tokens out. Tokens drip back in at a steady rate. A new key starts with a full bucket.

**Example:** capacity 5, 1 token per second, written `new RateLimit(5, Duration.ofSeconds(5))`. These rows are real output from the code.

| Time | What happens | Result |
|---|---|---|
| 0 s | 5 requests. The bucket goes 5 → 0 | 5 × **allowed** |
| 0 s | A 6th request. The bucket is empty | **denied**, retry in 1 s |
| 0.5 s | Only half a token has dripped in. A request needs a whole one | **denied**, retry in 0.5 s |
| 1.5 s | 1 whole token has arrived. The extra 0.5 s is *kept* toward the next token | **allowed** |
| 2.0 s | The kept 0.5 s plus 0.5 s more completes a token | **allowed** |
| 100 s | Idle for a long time, but the bucket holds at most 5 | 5 × **allowed**, then denied |
| any | Ask for 6 at once from a bucket that can only hold 5 | **throws** (can never succeed) |

**Common confusion:** a test that empties (drains) the bucket and then asks again at 0 ms is **denied**. That's correct: the bucket is empty and no time has passed. It's the `"0, 0"` row in `refillsLinearlyAndCapsAtCapacity`. A *brand-new* key asking at 0 ms is allowed, because it starts full.

**The code, `TokenBucket.refill`.** It runs only when a request arrives, never on a timer:

```java
long earned = elapsed / nanosPerToken;           // whole tokens only; integer math, no drift
if (earned >= capacity - tokens) {
    tokens = capacity; lastRefill = now;          // full: idle time beyond "full" is NOT banked
} else {
    tokens += earned;
    lastRefill += earned * nanosPerToken;         // keep partial progress toward the next token
}                                                 // (setting lastRefill = now leaks rate; tested)
```

Two rules hide in those lines:

- **Keep partial progress.** At 1.5 s, `lastRefill` moves to 1.0, not 1.5. Setting it to `now` would throw away 0.5 s every time, and the real rate would end up lower than configured.
- **Don't bank idle time.** When the bucket is full, `lastRefill` jumps to `now`. Otherwise a key idle for an hour would get a huge burst.

When a request is denied, the wait is exact: the k-th missing token arrives `k × nanosPerToken` after `lastRefill`.

No background thread ever refills a bucket. Refill is worked out when a request arrives, so 1M idle keys cost zero CPU.

#### Sliding-window log (exact)

**Idea:** remember the time of every granted permit. A new request is allowed only if fewer than N grants happened in the last W.

**Example:** limit 5 per second (from `SlidingWindowLogBehaviour`).

| Time | What happens | Result |
|---|---|---|
| 0 ms | Ask for 2 | **allowed**. The log is [0, 0] |
| 400 ms | Ask for 3 | **allowed**. The log is [0, 0, 400, 400, 400], which is full |
| 400 ms | Ask for 2 | **denied**. Both 0 ms grants must expire first, at 1000 ms: retry in 600 ms |
| 400 ms | Ask for 3 | **denied**. A 400 ms grant must expire too, at 1400 ms: retry in 1000 ms |

**Rule:** retry wait = (the oldest grant that must expire) + W − now. A grant stops counting exactly one window later: denied at 999 ms, allowed at 1000 ms.

#### Sliding-window counter (approximate, cheap)

**Idea:** keep only two counts, this window's and the previous window's. Estimate the last W as `previous × (part of the previous window still inside the last W) + current`.

**Example:** limit 10 per second (from `SlidingWindowCounterBehaviour`).

| Time | What happens | Result |
|---|---|---|
| 0–1000 ms (window #1) | 10 requests | 10 **allowed** |
| 1000 ms (start of window #2) | Estimate = 10 × 1.0 + 0 = 10, which is full | **denied**, retry in 100 ms |
| 1500 ms (halfway) | Estimate = 10 × 0.5 = 5 | 5 more **allowed** |

**Catch:** it assumes the previous window's requests were spread evenly. If they all came at its very end, a real 1-second span can see nearly 2× the limit (tested: 18 grants at limit 10).

#### Fixed window (the trap, not coded)

**Idea:** count per calendar window, e.g. per minute, and reset at the boundary.

**Example:** limit N per minute. N requests at 0:59 are allowed, and N more at 1:00 are allowed too. That's 2N in about one second.

#### Comparison

| Algorithm | State per key | Time per call | Accuracy | Behaviour | Use when | Don't use when |
|---|---|---|---|---|---|---|
| **Fixed window** (not coded; it's a trap) | 2 longs | O(1) | **2× burst at the boundary**: N at 0:59, N more at 1:00 | Resets on a tick | Coarse quotas ("1M calls/month") where the boundary doesn't matter | Protecting a backend from bursts |
| **Token bucket** ([`TokenBucket`](src/main/java/com/lld/ratelimiter/algorithm/TokenBucket.java)) | 2 longs | O(1) | Exact *for its own model* | Burst up to N, then a steady N/W | Default for API limits. Stripe, AWS API Gateway and Guava are all token-bucket family | You need "never more than N in any W". It allows ~2N in a W (tested) |
| **Sliding-window log** ([`SlidingWindowLog`](src/main/java/com/lld/ratelimiter/algorithm/SlidingWindowLog.java)) | O(N) timestamps | O(1) amortised; O(N) to compute a deny's wait | **Exact**: ≤ N in every window `(t−W, t]` | No bursts beyond N | Small N with a hard guarantee: login attempts, OTP sends, password resets | N in the thousands per key. Memory is ~24 B per grant, so 10k/min ≈ 240 KB per hot key (estimate) |
| **Sliding-window counter** ([`SlidingWindowCounter`](src/main/java/com/lld/ratelimiter/algorithm/SlidingWindowCounter.java)) | 3 longs | O(1) | **Approximate**: assumes the previous window was uniform | Smooth | High-volume edge limits where O(1) memory matters (Cloudflare's choice) | Adversarial clients can cluster traffic. Worst case nears 2× (tested: 18 grants in one real second at limit 10) |
| **Leaky bucket (queue)** | a queue | O(1) | n/a | *Shapes* output: adds waiting instead of rejecting | Smoothing traffic to a fragile downstream | You need an immediate yes/no |
| **GCRA** | 1 long (TAT) | O(1) | Same as token bucket | | Single-field state is ideal for Redis | Interviews, unless asked. It's harder to explain in 45 min |

### Concurrency model

- **Shared state:** one mutable state object per key, stored in a `ConcurrentHashMap<String, S>` (CHM).
- **Invariant:** for one key, "read time → check → consume" is atomic. So if N callers race, at most `limit` of them get in.
- **Primitive:** `ConcurrentHashMap.compute(key, fn)`. Its Javadoc guarantees the whole function runs atomically for that key. Callers on the same key take turns. Callers on different keys run in parallel unless they share a hash bin. There's no global lock.
- **Why not `synchronized` on the whole limiter?** It's correct, but every key would wait in one line. One hot key would slow down everyone.
- **Why not `AtomicLong` + compare-and-set (CAS)?** It works for one counter. But token bucket updates two fields together (`tokens`, `lastRefill`). You'd need an immutable state object and a CAS loop on an `AtomicReference`. That's valid and lock-free, but there's more to get wrong in 45 minutes.
- **Why read the time *inside* the lock?** If you read it before `compute`, thread A (t=100) can get the lock after thread B (t=105). Time then goes backwards for that key, and `elapsed` turns negative.
- **Why `System.nanoTime`, not `currentTimeMillis`?** Wall clocks can jump (NTP sync, leap smear, someone changing the clock). `nanoTime` only moves forward within a JVM. That's also why `Ticker` exists: tests use a `FakeTicker` and never sleep.
- **Rules for the lambda:** keep it short. Never touch *another* key of the same map inside it. That can deadlock or throw `IllegalStateException: Recursive update`.

**How much does atomic matter?** A check-then-act version (`get` → compare → `put`, each step atomic on its own, but not together) was run against this module's `ConcurrencyTest`. 19 of 20 runs let too many through: from 1,004 up to all 6,400 attempts, against a limit of 1,000. Lost updates keep the counter low, so there's no ceiling on the over-grant (measured on an Apple-silicon laptop, 32 threads).

### Eviction: bounded memory without changing any decision

*Eviction* means deleting a key's state to save memory.

- `isIdle(state, now)` means "this state behaves exactly like a fresh one". Example: a token bucket that would be full by now.
- `evictIdle()` removes such keys with `computeIfPresent` (returning `null` removes the entry). It holds the **same per-key lock** as `tryAcquire`, so it can't race a request in flight.
- `RateLimiterContractTest.evictionIsLossless` runs 2,000 random steps with and without eviction after every call, and checks the decisions are identical.

The sliding-window counter needed a fix to make this true. When both counters are zero, it re-aligns the key's window start. Otherwise a kept key and a fresh key would disagree about where windows begin.

### Code and tests

| File | What to look at |
|---|---|
| [`KeyedRateLimiter`](src/main/java/com/lld/ratelimiter/KeyedRateLimiter.java) | `compute` + holder array; `evictIdle` |
| [`RateLimiterFactory`](src/main/java/com/lld/ratelimiter/RateLimiterFactory.java) | `create(Algorithm, RateLimit, Ticker)`; enum `TOKEN_BUCKET`, `SLIDING_WINDOW_LOG`, `SLIDING_WINDOW_COUNTER` |
| [`RateLimitAlgorithm`](src/main/java/com/lld/ratelimiter/algorithm/RateLimitAlgorithm.java) | the strategy interface: `newState`, `tryAcquire`, `isIdle` |
| [`TokenBucket`](src/main/java/com/lld/ratelimiter/algorithm/TokenBucket.java) | integer refill with carry-over; exact `retryAfter` |
| [`SlidingWindowLog`](src/main/java/com/lld/ratelimiter/algorithm/SlidingWindowLog.java) | wait = the *k*-th oldest grant + W − now |
| [`SlidingWindowCounter`](src/main/java/com/lld/ratelimiter/algorithm/SlidingWindowCounter.java) | solving the estimate formula for `retryAfter` |
| [`RateLimiterContractTest`](src/test/java/com/lld/ratelimiter/RateLimiterContractTest.java) | one contract, run against all 3 via `@EnumSource` |
| [`AlgorithmBehaviourTest`](src/test/java/com/lld/ratelimiter/AlgorithmBehaviourTest.java) | what makes each algorithm different, including the two "~2×" tests |
| [`ConcurrencyTest`](src/test/java/com/lld/ratelimiter/ConcurrencyTest.java) | 32 threads, start gate, frozen time, so exactly `limit` grants |
| [`app/…/ratelimiter`](../app/src/main/java/com/lld/app/ratelimiter/) | HTTP adapter: 429 + `Retry-After` |

Run it: see [`commands.md`](commands.md).

---

## 3. The Gaps

### Follow-up rounds (this is where staff is decided)

**F1: "Free users get 10/min, paid get 1000/min, and ops wants to change limits without a deploy."**

- **Change:** add a `LimitPolicy` that maps `key → RateLimit` (e.g. by tier). Keep **one `KeyedRateLimiter` per distinct limit** in an immutable `Map<RateLimit, KeyedRateLimiter>`.
- Publish the map through a `volatile` field or an `AtomicReference`. A reload builds a new map and swaps it in one write. Readers see the whole old config or the whole new one, never a mix.
- **The trap:** what happens to existing state on reload? Dropping it resets everyone's limit, so a reload becomes a free burst.
  - Keep limiters whose `RateLimit` didn't change.
  - For changed ones, either accept the reset, or clamp tokens to `min(tokens, newCapacity)`. Say which one you chose.
- **Staff extension: compound limits** ("10/s *and* 1000/day"). Both must pass, or neither consumes. Two limiters means two locks, so the options are:
  - Check both, then consume both. That's the race again.
  - Consume A, and if B denies, **refund** A. That needs a `release(key, n)` on the interface.
  - Store both limits in **one state object** under one key lock. Simplest and atomic, so pick this.
  - This is the multi-object atomicity problem from [Topic 08](../08-multi-object-atomicity/).

**F2: "We have 50M distinct keys a day. An attacker sends random API keys."**

- **Memory estimate** (back-of-envelope, not measured): a token-bucket state is ~32 B, a CHM node ~32 B, and a short `String` key ~48 B, so **~110 B per key**. 1M live keys ≈ 110 MB.
- Eviction keeps "live" close to the keys active within about one window, not every key seen today.
- **The attack:** every unknown key creates state. Mitigations, in order:
  1. **Limit by a coarse key first** (IP, /24 prefix), before checking the API key. Unknown keys then can't create per-key state.
  2. **Don't create state for a request that leaves it fresh.** Token bucket at `permits=1` still creates it, so a cheaper trick is a small global limiter on *creating new keys*.
  3. **A bounded map with size-based eviction (LRU, Caffeine `maximumSize`) loses data, and here that means *fail-open*.** Evicting a non-idle key resets its limit, so an attacker who churns the cache resets their *own* limit. Prefer time-based expiry. Caffeine `expireAfterAccess(W)` is lossless for token bucket and sliding log (use `2W` for the counter), because a bucket idle for W is full by definition.
  4. **Count-min sketch** for fixed memory. It over-counts, so it fails *closed* on innocent keys that collide. That's fine for abuse detection, not for paid quotas.
- `evictIdle()` is O(keys), so run it on a schedule (the `app` runs it every 30 s). Don't run it on every request.

**F3: "It runs on 40 instances behind a load balancer."**

- **The problem:** each instance enforces its own copy of the limit. A client spread round-robin gets up to **40 × limit**.
- **Options:**

  | Option | How | Cost | Failure mode |
  |---|---|---|---|
  | Divide | each instance enforces `limit / K` | none | wrong on autoscale, uneven LB, small limits (10/40 = 0) |
  | Sticky routing | consistent-hash key → instance ([Topic 29](../29-consistent-hashing/)) | LB config | rebalancing resets limits; hot key = hot instance |
  | **Central store** | Redis + Lua script: refill + check + consume in one atomic `EVAL` | +1 network round trip per request (sub-ms in-region; measure yours) | Redis down → you must choose fail-open (with a local fallback limiter) or fail-closed |
  | Local + async sync | decide locally, share counts every ~100ms | small | over-admits by up to (sync interval × rate × K) |

- **Redis details a staff candidate mentions:**
  - Use **Lua, not GET-then-SET**. GET-then-SET is the same check-then-act race, just over a network.
  - Read the time **inside the script** (`redis.call('TIME')`), so clients with different clocks agree.
  - Set a key TTL ≈ W so Redis deletes idle keys. That's the F2 answer for free.
  - Watch for one hot key pinned to one Redis Cluster shard.
  - Give the limiter a **tight timeout** (a few ms). A slow limiter is worse than no limiter.
- See [Redis-poc rate limiting](../../pocs/Redis-poc/11-patterns/02-rate-limiting/) and [its capstone gateway](../../pocs/Redis-poc/15-capstones/01-rate-limited-api-gateway/). The system-level view (admission control, load shedding) is in [SystemDesign-poc 64](../../pocs/SystemDesign-poc/64-rate-limiting-and-admission-control/).

### Common mistakes

| Mistake | Why it's wrong | Seen in |
|---|---|---|
| `if (count.get() < N) count.incrementAndGet()` | Check-then-act. Two threads both pass the check | most first drafts |
| A `HashMap` + `synchronized` per method, or one global lock | Correct, but every key waits in one line | "safe" drafts |
| Reading the clock before taking the lock | Time goes backwards for the key, so `elapsed` is negative | subtle; rarely caught |
| `System.currentTimeMillis()` | NTP jumps make refill negative or huge | |
| `lastRefill = now` on every refill | Throws away partial progress, so the real rate is lower than configured | `partialProgressTowardTheNextTokenIsKept` |
| A background thread refilling every bucket | O(keys) CPU even while idle; doesn't scale | |
| Calling fixed window "sliding" | 2× at the boundary | `hasNoBoundaryBurst` shows the log doesn't |
| Thinking token bucket caps requests per window | It allows ~2N in a window: N burst + N refill | `isNotAPerWindowCap…` |
| Returning `boolean` | The caller can't set `Retry-After`, so clients retry in a storm | |
| 429 for `permits > limit` | It never succeeds, so the client retries forever | `rejectsRequestsThatCanNeverSucceed` |
| LRU eviction of limiter state | Evicting a live key resets its limit (fail-open) | |
| `Retry-After` in milliseconds | HTTP `Retry-After` is in **seconds** (round *up*) | `app/` adapter |
| `Thread.sleep` in tests | Slow and flaky; inject the clock instead | `FakeTicker` |

### When NOT to use a rate limiter

- **Protecting total capacity:** per-key limits don't stop 1M well-behaved clients from overloading you together. You need concurrency limits or adaptive load shedding (see microservices-poc [27](../../pocs/microservices-poc/27-rate-limiting-load-shedding/)).
- **Downstream cost varies 100×:** count *cost*, not requests (`permits = cost`), or limit concurrency instead.
- **Billing quotas:** these need durable, auditable counters, not an in-memory approximation.

---

## 4. Official References

- `ConcurrentHashMap.compute`, the atomicity guarantee and "must not attempt to update any other mappings": https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html#compute(K,java.util.function.BiFunction)
- `System.nanoTime`, monotonic and only meaningful as a difference: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()
- RFC 6585 §4, `429 Too Many Requests`: https://www.rfc-editor.org/rfc/rfc6585#section-4
- RFC 9110 §10.2.3, `Retry-After` (delay-seconds): https://www.rfc-editor.org/rfc/rfc9110#field.retry-after
- IETF draft, `RateLimit` header fields: https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/
- Stripe, *Scaling your API with rate limiters*: https://stripe.com/blog/rate-limiters
- Cloudflare, *How we built rate limiting capable of scaling to millions of domains* (sliding window counter, 0.003% error on real traffic): https://blog.cloudflare.com/counting-things-a-lot-of-different-things/
- Guava `RateLimiter` (a blocking, smooth token bucket): https://guava.dev/releases/snapshot-jre/api/docs/com/google/common/util/concurrent/RateLimiter.html
- Redis rate-limiting patterns: https://redis.io/learn/howtos/ratelimiting
- Envoy global rate limiting (the "central service" option at scale): https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/other_features/global_rate_limiting
- Brandur, *Rate limiting, cells, and GCRA*: https://brandur.org/rate-limiting

---

## 5. 45-Minute Interview Timeline

| Min | Do | Say out loud |
|---|---|---|
| 0–5 | Clarifying questions (table above); write v1 requirements | "In-process first, distributed as a follow-up?" |
| 5–10 | `RateLimiter` interface + `Decision`; pick token bucket, name the alternatives in one line each | "Boolean isn't enough. The caller needs `Retry-After`." |
| 10–25 | Code `TokenBucket` math, then `KeyedRateLimiter` with `compute` | "Time is read inside the lock, and here's why." |
| 25–33 | Tests: burst = N, refill, per-key isolation, concurrency (latch + frozen clock) | "This test fails against a check-then-act version." |
| 33–45 | Follow-ups F1 → F3; eviction if not already done | "In-process means K × limit. Here are 4 options." |

If you're behind at minute 25, skip the sliding-window code. Name it, give its costs, and move on. Correct concurrency beats a second algorithm.

## 6. Senior vs Staff Rubric

| Area | Senior (hire) | Staff (strong hire) |
|---|---|---|
| Algorithm | Codes token bucket correctly | Also says when it's wrong (~2N per window) and picks the log for login limits |
| Concurrency | Uses a concurrent map + per-key lock | Explains *why* atomic per key, why time goes inside the lock, and why not a global lock or CAS |
| API | Returns allow/deny | `Decision` with `remaining`/`retryAfter`; `permits > limit` is a programming error, not a 429 |
| Tests | Happy-path unit tests | Fake clock, a concurrency test that would catch the race, a property test for eviction |
| Memory | "Use a TTL cache" | Shows LRU eviction fails open; proposes lossless idle eviction |
| Distributed | "Use Redis" | Lua for atomicity, server-side time, fail-open vs fail-closed decided per limit, timeout budget |
| Drives | Waits for follow-ups | Raises F2/F3 before being asked; cuts scope on purpose when time is short |

## 7. Key Takeaways

1. The heart of the problem is **"read time → check → consume" in one atomic step per key**. `ConcurrentHashMap.compute` gives you that without a global lock.
2. Keep each algorithm as simple single-threaded math, and keep all concurrency in one wrapper. Test each separately.
3. **Token bucket** is the default, and it allows bursts: ~2N in any window. **Sliding log** is exact but uses O(N) memory. **Sliding counter** is O(1) but approximate, up to ~2× in the worst case. **Fixed window** has the 2× boundary burst.
4. Inject a clock that only moves forward. Tests move time forward instead of sleeping.
5. Return a `Decision` (remaining, retryAfter), and throw for requests that can never succeed.
6. Memory: delete only **idle** keys (state equals a fresh one). Size-based eviction resets live limits and fails open.
7. In-process limiters allow **K × limit** behind K instances. The fix (Redis + Lua) adds a network hop and a fail-open-or-closed decision.
8. Refill when a request arrives. Never run a background thread per key.

---

**Self-grade: STRONG.** It has the full spine, a worked example before each algorithm, all three algorithms coded and tested, measured numbers where measured (the check-then-act over-grant), estimates labelled as estimates (memory per key), and explicit failure modes and when-not-to. The known gap: the Redis/Lua version is described, not coded. That lives in Redis-poc.

[← Topic 20: Library Management](../20-library-management/) · [Index](../README.md) · [Topic 22: Bounded Blocking Queue & Thread Pool →](../22-blocking-queue-thread-pool/)
