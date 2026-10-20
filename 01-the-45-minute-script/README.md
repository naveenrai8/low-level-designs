# Topic 01: The 45-Minute Script

Most failed LLD rounds aren't failures of knowledge. They're failures of the clock. The candidate
spends 15 minutes on requirements, codes in silence, reaches the concurrency question at minute
40, and never sees a follow-up. That's where the level is decided. A script fixes this. It
gives you fixed checkpoints, a sentence to say at each one, and pre-decided rules for what to cut
when you're behind. The running example throughout is the
[rate limiter](../21-rate-limiter/).

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

| Without a script | Symptom the interviewer writes down |
|---|---|
| Open-ended clarifying | "Took 15 min to start; needed prompting to converge" |
| Coding before the API exists | "Interface changed three times mid-code" |
| Silent coding | "Hard to assess reasoning" |
| Concurrency left to the end | "Didn't address thread safety" (it's the core of the problem!) |
| No time for follow-ups | "Couldn't evaluate extensibility". At staff level this is close to a no-hire |
| Rewrite on a follow-up | "Design wasn't extensible" |

A 45-minute round has about **35 minutes of usable time**, after introductions and your questions
at the end (estimate). Every checkpoint below is set against that.

---

## 2. How It Solves It

### The script

```
 0        5            15                         40      45
 |-clarify-|--model+API--|---------code-------------|-tests+F-|
     5          10                  25                   10*
 * tests & follow-ups overlap: name tests while coding, spend the tail on F1/F2
```

| Min | Phase | Output on the board | Say this out loud |
|---|---|---|---|
| 0–5 | **Clarify** | 4–6 numbered v1 requirements + an "out of scope" line | "Here's what I'll build in v1. Anything you'd add or cut?" |
| 5–15 | **Model + API** | Entities with invariants, then the public interface, signatures only | "This is the contract a caller sees. Let me check it with you before I implement." |
| 15–40 | **Code** | Core path first, then edge cases, then concurrency | "The invariant I'm protecting here is X; the primitive is Y because Z." |
| 40–45 | **Tests + follow-ups** | 3–5 named test cases; the F1 design delta | "If you added X, here's what changes: these two classes, and nothing else." |

### Minutes 0–5: clarify with assumed answers

Don't ask open questions ("what should it do?"). Ask **closed questions with a default**. That
way silence still lets you move on.

```
Rate limiter, said aloud:
  "I'll assume in-process for v1 and treat distributed as a follow-up — OK?"
  "Reject rather than queue?"   "Bursts up to N are fine?"
  "Key is an opaque string the caller builds — user, API key, IP?"
  "Can one request cost more than 1 permit?"
Write:  v1: (1) tryAcquire(key) → allow/deny, non-blocking  (2) N per W, same for all keys
            (3) keys independent  (4) deny says retryAfter  (5) thread-safe  (6) bounded memory
        Out of scope: queueing, per-tier config, distributed, HTTP.
```

Rule: **stop at 5 minutes even if questions remain.** Write your remaining assumptions down and
move on.

### Minutes 5–15: interface first, then entities

The order matters. The interface is what the interviewer agrees to. Entities are how you deliver
it.

```java
// 1. The contract (write this first, get a nod)
interface RateLimiter {
    Decision tryAcquire(String key, int permits);   // all-or-nothing; IAE if permits > limit
}
record Decision(boolean allowed, long remaining, Duration retryAfter) {}

// 2. The entities + invariants (one line each, as comments if short on time)
record RateLimit(long permits, Duration window) {}  // permits >= 1, window > 0
// per-key state: tokens <= capacity; check-and-consume atomic per key
```

At this checkpoint, **name the algorithm and its alternatives in one line each**: "Token bucket:
O(1), allows bursts. A sliding log is exact but O(N) memory. A fixed window has a 2× boundary
burst." Then move on. The comparison is the signal; coding all three isn't.

### Minutes 15–40: code in this order

1. **The happy path, single-threaded:** `TokenBucket` refill + consume. That's about 10 minutes.
2. **Edge cases:** permits > limit, blank key, time going backwards, cap at capacity.
3. **Concurrency:** wrap the state in `ConcurrentHashMap.compute`. Narrate it with the
   four-line template below.
4. **Resource bounds:** eviction of idle keys. One method, or name it if you're behind.

**The concurrency narration template.** Say all four lines, every time:

```
Shared state:  per-key bucket in a map
Invariant:     read-time → check → consume is atomic per key (never over-grant)
Primitive:     ConcurrentHashMap.compute — per-key lock, no global lock
Why not X:     synchronized serialises all keys; CAS needs an immutable 2-field state
```

### Minutes 40–45: tests, then follow-ups

Name tests as a list even if you only write one. Graders credit the *list*:

```
burst of N+3 at one instant → exactly N allowed
after W of quiet → full N again
deny.retryAfter, then advance clock by it → allowed       (fake clock, no sleep)
alice exhausted, bob unaffected
32 threads × 200 on one key with time frozen → exactly N  (latch start gate)
```

For each follow-up, answer as a **design delta**, never a rewrite:

```
F: "free = 10/min, paid = 1000/min, reload without deploy"
Delta:  + LimitPolicy (key → RateLimit)        new
        + Map<RateLimit, KeyedRateLimiter>     new, swapped atomically on reload
        ~ caller resolves policy first          changed, 1 line
        = TokenBucket, KeyedRateLimiter         UNCHANGED  ← say this explicitly
Risk:   reload drops state → free burst; keep limiters whose RateLimit didn't change
```

The "UNCHANGED" line is the payoff of having designed the interface first. Say it out loud.

### When the follow-up invalidates your design

Some follow-ups are designed to break v1 (e.g. "now 40 instances"). Don't defend v1, and don't
rewrite it. Follow this four-step response:

1. **Name what breaks:** "Each instance enforces its own copy, so the effective limit is 40×."
2. **Name what survives:** "The `RateLimiter` interface and all the callers survive. Only the
   implementation behind it changes."
3. **Offer 2–3 options with costs:** divide the limit, sticky routing, a central Redis with Lua.
4. **Pick one and say what you'd measure:** "Redis + Lua, with a tight timeout and fail-open
   for general APIs. I'd measure the p99 of the extra hop."

---

## 3. The Gaps

### Recovery tactics when behind

| You are at… | …but have | Cut this | Keep this |
|---|---|---|---|
| Min 10 | no agreed API | the remaining clarifying questions | write the interface *now* and ask "OK?" |
| Min 25 | no working core path | second algorithm, eviction, input validation | one algorithm end to end |
| Min 32 | no concurrency | the code for edge cases (list them as TODOs) | **concurrency**: it's the core of most Phase 3 problems |
| Min 38 | no tests | writing them | *naming* 4–5 tests aloud, 30s |
| Min 42 | no follow-up yet | everything | *ask* for the follow-up: "Want to see how this extends?" |

The rule behind the table: **correct core + concurrency + one follow-up beats a complete v1
with nothing else**. Cut breadth, never the core invariant.

### Misconceptions

| Belief | Reality |
|---|---|
| "More clarifying questions shows thoroughness." | After about 5 minutes it reads as stalling. Use closed questions with defaults |
| "Draw the full class diagram first." | Draw a box-and-arrow sketch in 2 minutes. The interface code *is* the design |
| "I'll add thread safety at the end." | In concurrency-heavy problems it changes the data structure. Decide it at the API step |
| "Silence while coding is focus." | Graders can only score what they hear. Narrate *decisions*, not keystrokes |
| "A follow-up means my design was wrong." | Follow-ups are the planned second half of the round. Treat them as expected |
| "Tests are optional in a design round." | Naming the concurrency test and the fake-clock test is a cheap, strong signal |

### Failure modes

- **Over-scoped v1:** six features half-done. Nothing runs, and the follow-ups never come.
- **Interface churn:** the signature changes mid-code because you skipped the 5–15 checkpoint.
- **Pattern-first:** "I'll use a Factory, a Strategy and an Observer" before there are
  requirements. See [Topic 02](../02-staff-signals-and-rubric/).

### When NOT to follow this script verbatim

- **Machine-coding rounds (90–120 min, must run):** keep the shape, but scale it. Spend roughly
  10 minutes clarifying and designing, 60–80 coding, and leave 15 for a demo and extension.
  Running code matters more there.
- **Discussion-only OOD:** skip the code phase and spend it on entities, invariants and
  follow-up deltas.
- **The interviewer steers:** if they jump straight to "how would you make it thread-safe?",
  follow them. The script is a default, not a contract.

---

## 4. Official References

- Atlassian, *Engineering interview handbook*: "what's most important is how you think",
  trade-offs; follow-up questions that "ladder" (stated for its systems design round):
  https://www.atlassian.com/company/careers/resources/interviewing/engineering
- Amazon, *SDE II Interview Prep*: real code, edge cases, "validate that no bad input can slip
  through": https://www.amazon.jobs/content/en/how-we-hire/sde-ii-interview-prep
- `java.util.concurrent` package summary (memory-consistency guarantees you narrate):
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html
- Worked example used throughout: [Topic 21: Rate Limiter](../21-rate-limiter/), including its
  §5 timeline and §6 rubric.

---

## 5. Key Takeaways

1. Use four checkpoints: **5 clarify / 10 model + API / 25 code / 5–10 tests + follow-ups**. Check the clock at each.
2. Clarify with **closed questions that carry a default**, and stop at 5 minutes.
3. **Interface first**, then get a nod. It's what makes follow-ups a delta instead of a rewrite.
4. Name alternatives in one line each. Code just one of them.
5. Narrate concurrency with the four lines: shared state, invariant, primitive, why not X.
6. Name tests as a list even if you write one. Include the fake-clock test and the concurrency test.
7. Answer follow-ups as **new / changed / UNCHANGED**, and say "unchanged" aloud.
8. When behind, cut breadth, never the core invariant. Ask for the follow-up yourself by minute 42.

---

[← Topic 00: LLD Round Formats](../00-lld-round-formats/) · [Index](../README.md) · [Topic 02: Staff Signals and Rubric →](../02-staff-signals-and-rubric/)

**Self-grade: STRONG.** It has concrete checkpoints, a word-for-word script, a cut-list table by
minute, the follow-up delta format, and when-not-to-use. The ~35 usable minutes is an estimate,
and it's labelled as one.
