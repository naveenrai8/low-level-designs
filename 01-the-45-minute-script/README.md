# Topic 01: The 45-Minute Script

Most failed LLD rounds fail on time, not knowledge. The candidate spends 15 minutes on requirements and then codes in silence. They reach the thread-safety question at minute 40 and never see a follow-up. The follow-up is where your level is decided. A script fixes this. It gives you fixed checkpoints, a sentence to say at each one, and rules for what to cut when you fall behind. The running example throughout is the [rate limiter](../21-rate-limiter/).

**Words used here.** A *checkpoint* is a minute mark by which something must be done. A *design delta* is the list of what is new, changed and unchanged when a requirement changes. An *invariant* is a fact that must always be true. A *permit* is one allowed request in the rate limiter.

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
| Open-ended questions | "Took 15 minutes to start; needed prompting" |
| Coding before the API exists | "Interface changed three times" |
| Silent coding | "Could not judge the reasoning" |
| Thread safety left to the end | "Did not address thread safety" (it is the heart of the problem) |
| No time for follow-ups | "Could not judge extensibility". At staff level this is close to a no-hire |
| Rewrite on a follow-up | "Design was not extensible" |

Example: you start coding at minute 25 instead of 15. You lose 10 of your 25 coding minutes, so you reach thread safety at minute 45, which is too late.

A 45-minute round has about **35 minutes of usable time** (an estimate). Introductions and your closing questions use the rest. Every checkpoint below is set against that.

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
| 0–5 | **Clarify** | 4 to 6 numbered v1 requirements and an "out of scope" line | "Here's what I'll build in v1. Anything you'd add or cut?" |
| 5–15 | **Model + API** | The main types with their rules, then the public interface (signatures only) | "This is the contract a caller sees. Let me check it with you before I implement." |
| 15–40 | **Code** | Main path first, then edge cases, then thread safety | "The invariant I'm protecting here is X; the primitive is Y because Z." |
| 40–45 | **Tests + follow-ups** | 3 to 5 named tests; the design delta for the first follow-up | "If you added X, here's what changes: these two classes, and nothing else." |

### Minutes 0–5: clarify with assumed answers

Do not ask open questions like "what should it do?". Ask **yes/no questions that carry a default answer**. Then, if the interviewer stays silent, you can still move on.

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

Rule: **stop at 5 minutes even if questions remain.** Write down what you assumed and move on.

### Minutes 5–15: interface first, then entities

The order matters. The interface is what the interviewer agrees to. The entities (the main types) are how you deliver it.

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

At this checkpoint, **name the algorithm and its alternatives in one line each**. For example: "Token bucket: constant time per request, allows bursts. A sliding log is exact but uses memory per request. A fixed window lets through twice the limit at the window edge." Then move on. The comparison is the signal. Coding all three is not.

### Minutes 15–40: code in this order

1. **The main path, one thread:** `TokenBucket` refills, then spends tokens. This takes about 10 minutes.
2. **Edge cases:** asking for more permits than the limit, a blank key, the clock going backwards, a bucket that cannot hold more than its capacity.
3. **Thread safety:** keep each key's state inside `ConcurrentHashMap.compute`, which locks one key at a time. Explain it with the four-line template below.
4. **Memory limits:** remove idle keys. Write one method, or just name it if you are behind.

**The thread-safety template.** Say all four lines, every time:

```
Shared state:  per-key bucket in a map
Invariant:     read-time → check → consume is atomic per key (never over-grant)
Primitive:     ConcurrentHashMap.compute — per-key lock, no global lock
Why not X:     synchronized serialises all keys; CAS needs an immutable 2-field state
```

### Minutes 40–45: tests, then follow-ups

Name your tests as a list, even if you only write one. Graders credit the *list*:

```
burst of N+3 at one instant → exactly N allowed
after W of quiet → full N again
deny.retryAfter, then advance clock by it → allowed       (fake clock, no sleep)
alice exhausted, bob unaffected
32 threads × 200 on one key with time frozen → exactly N  (latch start gate)
```

Answer each follow-up as a **design delta** (new, changed, unchanged), never a rewrite:

```
F: "free = 10/min, paid = 1000/min, reload without deploy"
Delta:  + LimitPolicy (key → RateLimit)        new
        + Map<RateLimit, KeyedRateLimiter>     new, swapped atomically on reload
        ~ caller resolves policy first          changed, 1 line
        = TokenBucket, KeyedRateLimiter         UNCHANGED  ← say this explicitly
Risk:   reload drops state → free burst; keep limiters whose RateLimit didn't change
```

The "UNCHANGED" line is the payoff of designing the interface first. Say it out loud.

### When the follow-up invalidates your design

Some follow-ups are meant to break v1, such as "now run on 40 servers". Do not defend v1 and do not rewrite it. Answer in four steps:

1. **Name what breaks:** "Each server keeps its own count, so the real limit is 40 times higher."
2. **Name what survives:** "The `RateLimiter` interface and every caller stay the same. Only the code behind the interface changes."
3. **Offer 2 or 3 options with costs:** split the limit across servers, send each user to one server, or use one shared Redis with a Lua script.
4. **Pick one and say what you would measure:** "Redis plus Lua, with a short timeout, and let traffic through if Redis is down for general APIs. I would measure the slowest 1% of the extra network call."

---

## 3. The Gaps

### Recovery tactics when behind

| You are at… | …but have | Cut this | Keep this |
|---|---|---|---|
| Min 10 | no agreed API | the rest of the questions | write the interface *now* and ask "OK?" |
| Min 25 | no working main path | a second algorithm, cleanup, input checks | one algorithm, start to finish |
| Min 32 | no thread safety | edge-case code (list it as TODOs) | **thread safety**: it is the heart of most Phase 3 problems |
| Min 38 | no tests | writing them | *naming* 4 or 5 tests aloud (30 seconds) |
| Min 42 | no follow-up yet | everything else | *asking* for it: "Want to see how this extends?" |

The rule behind the table: **a correct main path, thread safety and one follow-up beat a complete v1 with nothing else**. Cut breadth. Never cut the core invariant.

### Misconceptions

| Belief | Reality |
|---|---|
| "More questions shows thoroughness." | After about 5 minutes it looks like stalling. Ask yes/no questions with defaults |
| "Draw the full class diagram first." | Sketch boxes and arrows in 2 minutes. The interface code *is* the design |
| "I'll add thread safety at the end." | It can change your data structure. Decide it at the API step |
| "Silence while coding is focus." | Graders only score what they hear. Explain *decisions*, not keystrokes |
| "A follow-up means my design was wrong." | Follow-ups are the planned second half of the round. Expect them |
| "Tests are optional in a design round." | Naming a thread-safety test and a fake-clock test is a cheap, strong signal |

### Failure modes

- **Too much in v1:** six features, all half-done. Nothing runs and the follow-ups never come.
- **Interface churn:** the method signature keeps changing because you skipped the minute 5–15 step.
- **Pattern-first:** you say "I'll use a Factory, a Strategy and an Observer" before any requirement needs them. See [Topic 02](../02-staff-signals-and-rubric/).

### When NOT to follow this script verbatim

- **Machine-coding rounds (90 to 120 minutes, code must run):** keep the shape but scale it. Spend about 10 minutes on questions and design, 60 to 80 on code, and 15 on a demo and an extension. Running code matters most there.
- **Discussion-only rounds:** skip the code phase. Spend that time on the main types, their rules and follow-up deltas.
- **The interviewer steers:** if they jump to "how would you make it thread-safe?", follow them. The script is a default, not a contract.

---

## 4. Official References

- Atlassian, *Engineering interview handbook*: "what's most important is how you think", trade-offs; follow-up questions that "ladder" (stated for its systems design round): https://www.atlassian.com/company/careers/resources/interviewing/engineering
- Amazon, *SDE II Interview Prep*: real code, edge cases, "validate that no bad input can slip through": https://www.amazon.jobs/content/en/how-we-hire/sde-ii-interview-prep
- `java.util.concurrent` package summary (memory-consistency guarantees you narrate): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html
- Worked example used throughout: [Topic 21: Rate Limiter](../21-rate-limiter/), including its §5 timeline and §6 rubric.

---

## 5. Key Takeaways

1. Use four checkpoints: **5 clarify / 10 model + API / 25 code / 5–10 tests + follow-ups**. Check the clock at each.
2. Ask **yes/no questions that carry a default**, and stop at 5 minutes.
3. **Interface first**, then get a nod. It turns follow-ups into small changes instead of rewrites.
4. Name the alternatives in one line each. Code only one.
5. Explain thread safety in four lines: shared state, invariant, tool, why not the alternative.
6. Name tests as a list even if you write only one. Include a fake-clock test and a thread-safety test.
7. Answer follow-ups as **new / changed / UNCHANGED**, and say "unchanged" aloud.
8. When behind, cut breadth, never the core invariant. Ask for the follow-up yourself by minute 42.

---

[← Topic 00: LLD Round Formats](../00-lld-round-formats/) · [Index](../README.md) · [Topic 02: Staff Signals and Rubric →](../02-staff-signals-and-rubric/)

**Self-grade: STRONG.** It has concrete checkpoints, a word-for-word script, a cut-list table by minute, the follow-up delta format, and when-not-to-use. The ~35 usable minutes is an estimate, and it's labelled as one.
