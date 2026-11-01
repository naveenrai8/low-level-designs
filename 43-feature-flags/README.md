# Topic 43: Feature Flags

A feature flag is an if-statement you can change without a deploy. "Design a feature flag service" sounds easy until the interviewer asks: why does a user flip between A and B on refresh, why did turning a rollout up kick people out, and what happens when the config changes in the middle of a request.

A staff answer uses a stable hash for the percentage, a fixed order of checks, one immutable snapshot per evaluation, and events that can never slow the caller.

## Table of Contents

[0 Words](#0-words-used-in-this-topic) · [1 Problem](#1-the-problem) · [2 How It Solves It](#2-how-it-solves-it) · [3 Gaps](#3-the-gaps) · [4 References](#4-official-references) · [5 Timeline](#5-45-minute-interview-timeline) · [6 Rubric](#6-senior-vs-staff-rubric) · [7 Takeaways](#7-key-takeaways)

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Flag / variant** | A named switch and one of its possible answers. `new-checkout` can be `on` or `off`; an A/B/C test has three variants. |
| **Context** | Who is asking: a user id plus attributes such as plan=pro. |
| **Bucket** | A slot from 0 to 9,999 taken from a hash of the user. A hash turns text into a number that looks random but is always the same for the same text. One bucket is one basis point (0.01%), so 1,000 basis points is 10%. |
| **Salt** | Extra text added before hashing. Change it and everybody gets a new bucket. |
| **Rollout** | Giving a feature to a share of users. 10% means buckets 0 to 999. |
| **Rule** | "If plan is pro and age is at least 18, serve B." The first rule that matches wins. |
| **Kill switch** | A flag-level off button that beats everything else. |
| **Prerequisite** | "Flag B only runs if flag A serves `on` to this user." |
| **Snapshot** | The whole config frozen at one version. It never changes after it is built. |
| **Torn read** | Reading half from the old config and half from the new one, so the answer matches neither. |
| **Sampling** | Reporting only some users' evaluations, to keep the volume down. |

Threads, locks and executors are not re-taught here: see [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/). Injected clocks follow [Topic 10](../10-time-and-testability/).

## 1. The Problem

Teams want to ship code dark, turn it on for 1% of users, then 10%, then everyone, and turn it off in seconds if it breaks. A hard-coded `if (user.id % 10 == 0)` cannot be changed live, is not stable, and does not explain itself.

### v1 requirements (say these out loud)

- `evaluate(flag, context)` returns a variant and the reason.
- Targeting: rules on user attributes, plus a percentage rollout.
- The same user gets the same answer every time, on every server.
- Raising a rollout does not remove anyone who already had the feature (for a two-way split, see the slice-shift gap below).

### Clarifying questions (assumed answers)

| Question | Assumed answer |
|---|---|
| Local or a network call per check? | Local, in memory. The config is pushed in. A network call per check would make every request slower and less reliable. |
| What if the flag does not exist? | Return the caller's fallback with reason `FLAG_NOT_FOUND`. Never throw. |
| Which user id? | A stable one. An anonymous user (no id) has no target and no rollout, but attribute rules still apply; then the default. |

**Out of scope:** the admin UI, audit of who edited a flag, per-environment copies, streaming transport, experiment statistics.

## 2. How It Solves It

### Entities and invariants

| Entity | Invariant |
|---|---|
| `Flag` | Immutable. Every variant named anywhere (off, default, targets, rules, rollout) is one of its variants. Rule ids are unique. |
| `Rollout` | Weights are in buckets and add up to exactly 10,000, so every bucket has exactly one variant. |
| `FlagConfig` | A versioned snapshot. Built only if there are no prerequisite cycles, no missing flags, and no chain deeper than 5. |
| `InMemoryFeatureFlags` | Holds one `AtomicReference<FlagConfig>`. One evaluation reads it once. |

```
 evaluate(flag, user)
        |
        v   read the live snapshot ONCE
 +-------------------------------------------------+
 | 1 kill switch off?          -> off variant      |
 | 2 prerequisites all met?    -> else off variant |
 | 3 user in target list?      -> that variant     |
 | 4 rules, in order           -> first match wins |
 | 5 percentage rollout        -> bucket's variant |
 | 6 default                                       |
 +-------------------------------------------------+
        |
        +--> maybe emit an event (only if user is sampled)
```

### The interface

```java
public interface FeatureFlags {
    Evaluation evaluate(String flagKey, Context context, String fallback);  // non-null arguments; never throws for odd user data
    ApplyResult apply(FlagConfig config);                                    // APPLIED or STALE
    long version();
}
```

Error contract: a bad config throws `InvalidConfigException` while it is built, so it can never reach `apply`. A config whose version is not above the live one returns `STALE`.

### Bucketing, with a worked example first

Flag `checkout`, salt `s1`. We hash the text `checkout NUL s1 NUL user-1` (NUL is a separator character) and take the number modulo 10,000. The first two rows are real values from the tests; the last two are made up to show the line moving.

| User | Bucket | In at 10% (0..999)? | In at 20% (0..1999)? |
|---|---|---|---|
| user-1 | 5021 | no | no |
| user-2 | 5955 | no | no |
| (made up) | 700 | yes | yes |
| (made up) | 1500 | no | **yes** (new at 20%) |

Raising 10% to 20% only moves the line from 1000 to 2000. Nobody at 0..999 is removed.

- **Stated hash.** `Murmur3.hash32` is MurmurHash3 x86 32-bit, written out by hand and pinned to published answers. `String.hashCode` has a specified formula, but it mixes poorly on keys like `user-1` and `user-2`, and it works on Java UTF-16 chars, so only Java can reproduce it. Every SDK language must compute the same bucket, and Murmur3 has standard ports. It is not secure; we need an even spread, not secrecy.
- **Flag key in the hash.** Without it, the same first 10% of users would be the guinea pigs for every flag. With it, two 10% flags overlap about 1% (measured 1,057 of 100,000 users; independent would be 1,000, fully correlated 10,000).
- **Salt.** Change it to re-shuffle one flag, for example after a bad experiment.
- **10,000 buckets** give steps of 0.01%. Edges are tested: bucket 999 is in and 1000 is out at 10%; 0% is nobody; 100% is everybody.
- **Modulo bias.** 2^32 is not a multiple of 10,000, so each of the lowest 7,296 buckets gets 429,497 hash values and the rest get 429,496 (about 1 in 430,000).
- **Measured spread** (100,000 users `user-0..user-99999`, flag `checkout`, salt `s1`, JDK 21.0.8, one run; the hash is deterministic): share at 10% is 0.1000, at 50% is 0.5010, chi-square over 100 bins (99 degrees of freedom) is 100.3 against a critical value of 148.2 at 0.1% (from chi-square tables).

### Rules, with a worked example first

Rule order is `[pro: plan = pro -> B, adult: age >= 18 -> C]`, default `A`.

| User attributes | Rule `pro` | Rule `adult` | Result |
|---|---|---|---|
| plan=pro, age=30 | match | (not reached) | **B**, rule `pro` |
| plan=free, age=30 | no | match | **C**, rule `adult` |
| age=30 only | missing `plan` | match | **C**, and the detail says `rule pro: missing attribute plan` |
| plan=free, age="30" (a string) | no | type mismatch | **A**, detail says `type mismatch on age` |

All conditions in one rule must match (AND). A missing attribute or a wrong type never matches ("fail closed": when unsure, do not give the feature) and the reason goes in `detail`. Types are never coerced (`"5"` is not 5); numbers compare by exact value (5 equals 5.0); NaN, infinity and custom `Number` subclasses are not usable. Operators: EQ, NEQ, IN, GT, GTE, LT, LTE, STARTS_WITH.

An A/B/C split is `[A 3334, B 3333, C 3333]`: A owns buckets 0..3333, B owns 3334..6666, C owns 6667..9999. The weights must add up to exactly 10,000 or the config is refused: 9,999 leaves a bucket with no answer, 10,001 counts one twice.

### The concurrency model

- **Shared state:** the live config. **Invariant:** one evaluation sees one version, never a mix.
- **Primitive:** `AtomicReference<FlagConfig>` holding an immutable object. A reader calls `get()` once and passes that snapshot down, including to prerequisite checks. A writer uses compare-and-set and retries if another writer got there first.
- **Why not a lock:** readers are the hot path and a lock would make them wait for a config push.

### Code and tests

| File | What it is and its tests |
|---|---|
| [Murmur3](src/main/java/com/lld/flags/Murmur3.java), [Bucketing](src/main/java/com/lld/flags/Bucketing.java) | the hash and the bucket function. `BucketingTest` (11): pinned answers, independence, monotonic rollout, edges, spread |
| [Condition](src/main/java/com/lld/flags/Condition.java), [Rule](src/main/java/com/lld/flags/Rule.java), [Rollout](src/main/java/com/lld/flags/Rollout.java) | immutable targeting. `RuleTest` (6): order, fail closed, types, operators |
| [Flag](src/main/java/com/lld/flags/Flag.java), [FlagConfig](src/main/java/com/lld/flags/FlagConfig.java), `Prerequisite` | the config, checked when built. `PrecedenceTest` (11): each level beats the next, cycles, depth |
| [InMemoryFeatureFlags](src/main/java/com/lld/flags/InMemoryFeatureFlags.java) | evaluator and atomic swap. `ReloadTest` (3): no torn read, bad config, stale version |
| [BoundedEventBuffer](src/main/java/com/lld/flags/BoundedEventBuffer.java), `EventSink`, `EvaluationEvent` | the event path. `EventTest` (6): fields, sampling, exact drops, broken sink |
| (review hardening) | `HardeningTest` (13): off prerequisite, sampling independence and edge, lost apply race, notes, every build-time check |

## 3. The Gaps

### F1: precedence and prerequisites

The order is fixed, and a test for each step shows it beats the next: kill switch, prerequisites, individual targets, rules, rollout, default. A failed prerequisite beats a target on the dependent flag.

`B requires A = on`. A cycle (A needs B needs A, or A needs itself) is rejected when the config is built, with the path in the message. A missing flag or an unknown variant is rejected. The longest chain is 5 hops. A prerequisite that is switched off counts as unmet even if its off variant is the one required; LaunchDarkly says the same. Depth is capped but fan-out is not, and a prerequisite shared by several flags is re-decided each time; memoizing per evaluation (a map passed next to the snapshot) would fix that if it ever shows up in a profile.

LaunchDarkly documents off, then prerequisites, then individual targets, then rules top to bottom, then the default rule. This code matches that order, except that LaunchDarkly's percentage rollout lives inside the default rule, while here it is its own step before the default.

### F2: hot reload without torn reads

| Step | Evaluator | Config |
|---|---|---|
| 1 | starts `b`, reads snapshot v1 | v1 live |
| 2 | (test hook) another thread applies v2 | v2 live |
| 3 | still deciding `b`, checks prerequisite `a` using its v1 snapshot | |
| 4 | answer labelled version 1, all from v1 | |
| 5 | next evaluation reads v2 | |

The test uses a hook inside `evaluate`, so the swap happens at step 2 with no sleeping. A mutant where the prerequisite reads the live config is caught. An invalid config never goes live (it throws while building). Versions only go up: an older or equal version returns `STALE`, which protects you when two pushes arrive out of order. A second hook between the read and the compare-and-set proves a slower writer loses cleanly.

### F3: evaluation events and sampling

Each evaluation can emit `(time, flag, variant, reason, rule id, config version, user id)`, with the time from the injected `Clock`.

- **Deterministic sampling.** A user is sampled if their sampling bucket is below the rate. The bucket uses the flag key and salt + `/sample`, so it is independent of the rollout bucket and a user is always in or always out for that flag.
- **Cannot slow evaluation.** `BoundedEventBuffer` has fixed room. When full, a new event is dropped and counted (10 events into room for 3 gives exactly 7 drops). `emit` never blocks.
- **Cannot break evaluation.** A sink that throws is counted in `sinkFailures()` and swallowed.

### Misconceptions and common mistakes

| Myth or mistake | Reality |
|---|---|
| "Random per request is a fair 10%." | The same user would flicker between versions. Use a hash of a stable id. |
| "Check the live config each time you need it." | That is how torn reads happen. Read once. |
| "Raising a rollout never removes anyone." | True for a two-way split, or when you change the last slice. Changing an earlier slice's weight in an A/B/C split shifts every later slice, so users move. |
| "Hash only the user id." | The same users are first in every rollout. Hash the flag key too. |

### Trade-offs and production delta

- Too many long-lived flags become dead code and a testing matrix. Remove a flag when its rollout is 100%.
- Local evaluation means a change takes as long as the push to reach each server (estimate: seconds). Bucketing needs a stable id; anonymous traffic needs a cookie id or gets the default.
- Production adds config delivery by polling or streaming from a store with history and audit, schema validation and a dry run, segments and scheduled changes, per-environment copies, and metrics for evaluations per variant, config age and drops.
- Sink hand-off: a background thread batches the buffer to a queue; see [event-processing-poc](../../internal-pocs/event-processing-poc/).

## 4. Official References

- OpenFeature specification: https://openfeature.dev/specification/
- OpenFeature evaluation context: https://openfeature.dev/specification/sections/evaluation-context
- OpenFeature types (resolution reasons such as TARGETING_MATCH, DEFAULT, DISABLED): https://openfeature.dev/specification/types
- LaunchDarkly flag hierarchy (evaluation order): https://launchdarkly.com/docs/guides/flags/flag-hierarchy
- LaunchDarkly prerequisites (an Off prerequisite is unmet): https://launchdarkly.com/docs/home/flags/prerequisites
- LaunchDarkly targeting rules: https://launchdarkly.com/docs/home/flags/target-rules
- Unleash stickiness (stable hash for gradual rollout): https://docs.getunleash.io/reference/stickiness
- MurmurHash3 reference code: https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp
- MurmurHash overview: https://en.wikipedia.org/wiki/MurmurHash
- Java `AtomicReference`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-5 | Ask the questions in section 1. State: local evaluation, stable user id. |
| 5-12 | Draw the entities and the interface. Say the invariants. |
| 12-22 | Bucketing: hash of flag + salt + user, 10,000 buckets, monotonic rollout. Walk the table. |
| 22-30 | Rules: first match wins, fail closed on missing and wrong types. |
| 30-38 | F1 precedence ladder and prerequisites with cycle rejection. |
| 38-43 | F2: immutable snapshot plus atomic swap, one read per evaluation. |
| 43-45 | F3 sampling and the bounded buffer. Name the production delta. |

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Percentage | `hash(user) % 100 < p` | Hash includes flag key and salt, fixed hash named, monotonic and independent, edges tested |
| Rules | Works for the happy path | First match wins, fail closed with a reason, no type coercion |
| Order | "Rules then default" | Written ladder, each level tested against the next, prerequisites with cycle and depth limits |
| Reload | Reload the file | Immutable snapshot, one read per evaluation, stale versions ignored, bad config rejected |
| Events | Log every call | Per-user sampling, bounded buffer with exact drop count, sink cannot break the caller |

## 7. Key Takeaways

- Use a stated, stable hash of flag key, salt and user id. A rollout is a line over 10,000 buckets, so raising it only adds people.
- Fix the order of checks and test that each level beats the next.
- Fail closed and write down why.
- Read the config once per evaluation, and swap it as one immutable object.
- Reject bad configs when they are built, not when they are used.
- Telemetry must be bounded, per-user sampled, and unable to hurt the caller.

[← Topic 42: Workflow Engine](../42-workflow-engine/) · [Index](../README.md) · [Topic 44: Notification Service](../44-notification-service/)

**Self-grade: STRONG.** Remaining gap: no transport, no persistence of config history, no segments or scheduled changes; prerequisite fan-out is not memoized; the sink hand-off thread is described, not coded.
