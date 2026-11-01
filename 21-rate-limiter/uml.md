# Topic 21: Rate Limiter, UML

These are the formal UML pictures of the code in [`src/`](src/main/java/com/lld/ratelimiter/) and of the [`app/` HTTP adapter](../app/src/main/java/com/lld/app/ratelimiter/).

The [README](README.md#class-diagram-whiteboard-version) has the simple ASCII version you would draw on a whiteboard. These diagrams use Mermaid, so they stay plain text and render on GitHub and in most IDE Markdown previews.

| Diagram | The question it answers |
|---|---|
| [1. Core classes](#1-core-class-diagram) | Which class keeps the data, which classes do the math, and what gets passed in from outside? |
| [2. HTTP adapter classes](#2-http-adapter-class-diagram) | How does Spring plug the core in, without the core knowing about Spring? |
| [3. Sequence: one request](#3-sequence-one-request) | What happens, step by step, when one request comes in? Where is the lock? |
| [4. Sequence: idle-key eviction](#4-sequence-idle-key-eviction) | How is old data cleaned up without changing any answer? |

## How to read the symbols

| You see | It means |
|---|---|
| `+` before a name | public: anyone can use it |
| `-` before a name | private: only this class can use it |
| `~` before a name | package-private: only classes in the same package can use it |
| underlined name (`$` in the source) | static: belongs to the class, not to an object |
| *italic* method (`*` in the source) | abstract: no code here, a class that implements it must provide it |
| dotted line with a hollow triangle | "implements": the class at the plain end fills in that interface |
| solid line with a hollow diamond and arrow | "keeps a reference": the diamond side holds a field pointing at the arrow side |
| solid line with a filled diamond | "owns": the other type is nested inside, or is created and kept by this one |
| dotted arrow | "uses" or "creates": no field, just a call |

---

## 1. Core class diagram

**How to read this:** each box is one Java type. The top part of a box is its fields and the bottom part is its methods. The nested boxes are Java packages: `ratelimiter` is the main package `com.lld.ratelimiter`, and the `algorithm` box inside it is `com.lld.ratelimiter.algorithm`, where the three algorithms live. To keep the picture readable, two obvious links are not drawn: each algorithm keeps the `RateLimit` it was built with (see its `limit` field), and `RateLimiterFactory.create` builds the algorithm that matches the chosen `Algorithm` value.

**The one-sentence story:** `KeyedRateLimiter` keeps one small state object per user (key). For every request, it asks one `RateLimitAlgorithm` to do the math on that state.

```mermaid
classDiagram
    direction TB

    namespace com.lld.ratelimiter {
        class RateLimiter {
            <<interface>>
            +tryAcquire(String key) Decision
            +tryAcquire(String key, int permits)* Decision
        }
        class KeyedRateLimiter~S~ {
            <<final>>
            -RateLimitAlgorithm~S~ algorithm
            -Ticker ticker
            -ConcurrentHashMap~String,S~ states
            +KeyedRateLimiter(RateLimitAlgorithm~S~ algorithm, Ticker ticker)
            +tryAcquire(String key, int permits) Decision
            +evictIdle() int
            +trackedKeys() int
            +limit() RateLimit
            -validate(String key, int permits) void
        }
        class Ticker {
            <<interface>>
            +read()* long
            +system()$ Ticker
        }
        class Decision {
            <<record>>
            +boolean allowed
            +long remaining
            +Duration retryAfter
            +allow(long remaining)$ Decision
            +deny(long remaining, long retryAfterNanos)$ Decision
        }
        class RateLimit {
            <<record>>
            +long permits
            +Duration window
            +perSecond(long permits)$ RateLimit
            +perMinute(long permits)$ RateLimit
            +windowNanos() long
        }
        class RateLimiterFactory {
            <<final>>
            -RateLimiterFactory()
            +create(Algorithm algorithm, RateLimit limit, Ticker ticker)$ KeyedRateLimiter
        }
        class Algorithm["RateLimiterFactory.Algorithm"] {
            <<enumeration>>
            TOKEN_BUCKET
            SLIDING_WINDOW_LOG
            SLIDING_WINDOW_COUNTER
        }
    }

    namespace com.lld.ratelimiter.algorithm {
        class RateLimitAlgorithm~S~ {
            <<interface>>
            +newState(long nowNanos)* S
            +tryAcquire(S state, long nowNanos, int permits)* Decision
            +isIdle(S state, long nowNanos)* boolean
            +limit()* RateLimit
        }
        class TokenBucket {
            <<final>>
            -RateLimit limit
            -long capacity
            -long nanosPerToken
            +TokenBucket(RateLimit limit)
            -refill(State s, long nowNanos) void
            -tokensEarnedSince(State s, long nowNanos) long
        }
        class TokenBucketState["TokenBucket.State"] {
            -long tokens
            -long lastRefillNanos
        }
        class SlidingWindowLog {
            <<final>>
            -RateLimit limit
            -long windowNanos
            +SlidingWindowLog(RateLimit limit)
            -expire(State s, long nowNanos) void
        }
        class SlidingWindowLogState["SlidingWindowLog.State"] {
            -ArrayDeque~Long~ grants
        }
        class SlidingWindowCounter {
            <<final>>
            -RateLimit limit
            -long windowNanos
            +SlidingWindowCounter(RateLimit limit)
            -roll(State s, long nowNanos) void
            -estimate(State s, long elapsedInCurrent) double
            -retryAfterNanos(State s, long elapsed, int permits) long
        }
        class SlidingWindowCounterState["SlidingWindowCounter.State"] {
            -long windowStartNanos
            -long current
            -long previous
        }
    }

    RateLimiter <|.. KeyedRateLimiter : implements
    KeyedRateLimiter o--> "1" RateLimitAlgorithm : asks it to do the math
    KeyedRateLimiter o--> "1" Ticker : reads the time inside the lock
    KeyedRateLimiter ..> Decision : returns

    RateLimitAlgorithm <|.. TokenBucket : S = TokenBucket.State
    RateLimitAlgorithm <|.. SlidingWindowLog : S = SlidingWindowLog.State
    RateLimitAlgorithm <|.. SlidingWindowCounter : S = SlidingWindowCounter.State

    TokenBucket ..> TokenBucketState : creates and updates
    SlidingWindowLog ..> SlidingWindowLogState : creates and updates
    SlidingWindowCounter ..> SlidingWindowCounterState : creates and updates

    RateLimitAlgorithm ..> Decision : creates

    RateLimiterFactory ..> KeyedRateLimiter : creates, with the chosen algorithm
    RateLimiterFactory *-- Algorithm : nested enum
```

What the picture tells you:

| Point | In plain words | Where to look |
|---|---|---|
| **Data lives in one place** | Each user's state object (`*.State`) is stored only in `KeyedRateLimiter.states`. The algorithm classes keep no per-user data, so one algorithm object is shared by every user. | The arrows to the `*.State` boxes say "creates and updates", not "keeps a reference" |
| **One lock per user** | `ConcurrentHashMap.compute` locks just that user's entry while the check and the update happen. Two requests for the same user can't both slip through (Topic 07). The algorithms never lock anything themselves. | `KeyedRateLimiter.states` |
| **Time is passed in** | `Ticker` has one method, `read()`. Real code uses `System::nanoTime`, and tests use a fake clock that they move by hand (Topic 10). | `Ticker` |
| **Swappable algorithms (Strategy)** | All three algorithms implement the same `RateLimitAlgorithm<S>` interface. `S` is the type of that algorithm's own state object, so the compiler checks you never mix them up. | The three "S = …" lines |
| **Values check themselves** | `RateLimit` and `Decision` are records. Their constructors throw if you pass bad values, such as 0 permits or an "allowed" decision with a wait time. | The `<<record>>` boxes |
| **Why the algorithm package matters** | The algorithms live in a separate package, so the things they use must be public: `Decision.allow/deny` and `RateLimit.windowNanos()`. | The `+` on those methods |

---

## 2. HTTP adapter class diagram

**How to read this:** the boxes marked `core` come from diagram 1, and only the methods used here are shown. Everything else is Spring code in the `app/` module. Arrows only point from Spring code toward core code, never back. That's how the core stays free of Spring.

```mermaid
classDiagram
    direction LR

    class RateLimitController {
        <<RestController>>
        ~String REMAINING_HEADER$
        -RateLimiter limiter
        ~RateLimitController(RateLimiter limiter)
        ~acquire(String key, int permits) ResponseEntity
        ~retryAfterSeconds(Duration wait)$ long
    }
    class DecisionResponse["RateLimitController.DecisionResponse"] {
        <<record>>
        boolean allowed
        long remaining
        long retryAfterMillis
    }
    class RateLimiterConfig {
        <<Configuration>>
        -Logger log$
        ~rateLimiterTicker() Ticker
        ~rateLimiter(RateLimitProperties props, Ticker ticker) KeyedRateLimiter
        ~idleKeyEvictor(KeyedRateLimiter limiter) IdleKeyEvictor
    }
    class IdleKeyEvictor["RateLimiterConfig.IdleKeyEvictor"] {
        <<final>>
        -KeyedRateLimiter limiter
        ~IdleKeyEvictor(KeyedRateLimiter limiter)
        ~evict() void
    }
    class RateLimitProperties {
        <<record, ConfigurationProperties>>
        +RateLimiterFactory.Algorithm algorithm = TOKEN_BUCKET
        +long permits = 5
        +Duration window = 1s
        +Duration evictionInterval = 30s
    }

    class RateLimiter {
        <<interface, core>>
        +tryAcquire(String key, int permits) Decision
    }
    class KeyedRateLimiter~S~ {
        <<core>>
        +evictIdle() int
        +trackedKeys() int
    }
    class RateLimiterFactory {
        <<core>>
        +create(Algorithm, RateLimit, Ticker)$ KeyedRateLimiter
    }
    class Ticker {
        <<interface, core>>
        +system()$ Ticker
    }

    RateLimitController o--> RateLimiter : uses only the interface
    RateLimitController ..> DecisionResponse : JSON body
    RateLimiterConfig ..> RateLimitProperties : reads lld.rate-limit.*
    RateLimiterConfig ..> RateLimiterFactory : create(...)
    RateLimiterConfig ..> Ticker : Ticker.system() bean
    RateLimiterConfig *-- IdleKeyEvictor : nested, made as a bean
    IdleKeyEvictor o--> KeyedRateLimiter : calls evictIdle() on a timer
    RateLimiter <|.. KeyedRateLimiter
```

| Point | Why |
|---|---|
| The controller only knows `RateLimiter`, not `KeyedRateLimiter` | It only needs `tryAcquire`. Cleaning up old keys is a different job, done by a different bean. |
| A bad request (`IllegalArgumentException`) becomes HTTP 400 **inside the controller** | A global "any `IllegalArgumentException` = 400" handler would also hide real bugs in Spring as 400s (see `CLAUDE.md`, `app/` rules). |
| `Ticker` is a Spring bean | Tests can swap in a frozen clock. The core never calls `System.nanoTime` itself. |
| The algorithm and limit come from `application.yml` | `RateLimitProperties` reads `lld.rate-limit.*`, and the config hands those values to `RateLimiterFactory.create`. You can change the algorithm without changing code. |

---

## 3. Sequence: one request

**How to read this:** time flows from top to bottom. Each column is one object. A solid arrow is a method call, and a dashed arrow is the value coming back. The yellow box is the part that runs while the lock for this one user is held. `alt` means "one of these branches happens", and `opt` means "this only happens sometimes".

```mermaid
sequenceDiagram
    autonumber
    actor C as Client
    participant RC as RateLimitController
    participant KRL as KeyedRateLimiter
    participant CHM as ConcurrentHashMap<br/>(entry for key)
    participant T as Ticker
    participant A as RateLimitAlgorithm<S>

    C->>RC: POST /api/rate-limit/{key}?permits=n
    RC->>KRL: tryAcquire(key, n)
    KRL->>KRL: validate(key, n)
    alt blank key, n < 1, or n > limit.permits
        KRL-->>RC: throw IllegalArgumentException
        RC-->>C: 400 ProblemDetail
    else valid
        KRL->>CHM: compute(key, fn)
        rect rgba(255, 200, 0, 0.15)
            Note over CHM,A: locked for this key only, so check-and-update is one step
            CHM->>T: read()
            T-->>CHM: now
            opt first request for this key
                CHM->>A: newState(now)
                A-->>CHM: s
            end
            CHM->>A: tryAcquire(s, now, n)
            Note right of A: catch up (refill / expire / roll),<br/>check, then use up permits (changes s)
            A-->>CHM: Decision d
            CHM->>CHM: return s (kept in the map)
        end
        CHM-->>KRL: unlock
        KRL-->>RC: Decision d
        alt d.allowed
            RC-->>C: 200, X-RateLimit-Remaining
        else denied
            RC-->>C: 429, X-RateLimit-Remaining, Retry-After = seconds rounded up
        end
    end
```

Two things to say out loud while drawing it:

1. **The time is read inside the lock (step 7).** Suppose it were read before `compute`. Thread A reads t=100, thread B reads t=105, and then B gets the lock first. When A runs next, its "now" is older than the last update, so time goes backwards for this key and `elapsed` becomes negative (README, *Concurrency model*).
2. **Checking the input happens outside the lock (step 3).** Asking for more permits than the limit (`n > limit`) could never succeed. It throws (and becomes 400) instead of answering 429, because a "try again later" would make the client retry forever.

---

## 4. Sequence: idle-key eviction

**How to read this:** a timer wakes up every 30 seconds and walks over every key. For each key it takes the same per-key lock a request would take, and asks the algorithm, "is this state the same as a brand-new one?" If yes, the key is removed to save memory.

```mermaid
sequenceDiagram
    autonumber
    participant S as Spring scheduler
    participant E as IdleKeyEvictor
    participant KRL as KeyedRateLimiter
    participant CHM as ConcurrentHashMap
    participant T as Ticker
    participant A as RateLimitAlgorithm<S>

    S->>E: evict() every eviction-interval (30s)
    E->>KRL: evictIdle()
    KRL->>T: read()
    T-->>KRL: now
    loop each key in states.keySet()
        KRL->>CHM: computeIfPresent(key, fn)
        rect rgba(255, 200, 0, 0.15)
            Note over CHM,A: same per-key lock as tryAcquire, so no update is lost
            CHM->>A: isIdle(s, now)
            alt idle: a brand-new state would answer the same
                A-->>CHM: true
                CHM->>CHM: return null (key removed)
            else still remembers recent requests
                A-->>CHM: false
                CHM->>CHM: return s
            end
        end
    end
    KRL-->>E: number of keys removed
```

`isIdle` means "throwing this state away can't change any future answer". For example, a token bucket that has refilled to full is exactly the same as a new bucket, so it is safe to delete. A new one will be made the next time that user sends a request.

The loop goes over `keySet()` while other threads keep using the map. Java's `ConcurrentHashMap` allows this: the loop may or may not see keys added during the walk, which is fine here.

The README's [eviction section](README.md#eviction-bounded-memory-without-changing-any-decision) describes the test that proves eviction is safe. The test runs the same requests with and without eviction after every call, and the answers are identical.

[← README](README.md) · [Index](../README.md)
