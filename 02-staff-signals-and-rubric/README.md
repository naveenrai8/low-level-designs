# Topic 02: Staff Signals and the Self-Grading Rubric

Two candidates hand in the same working rate limiter. One is levelled senior and the other staff.
The difference is rarely in the code. The staff candidate stated the invariant before writing
the lock, cut scope out loud, raised the distributed problem before being asked, and answered
the follow-up by saying which classes *don't* change. If you can't see these signals, you can't
practise them, and a mock you can't grade teaches nothing. This topic names the signals and gives
you a rubric to score yourself after every mock.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

- **Level is decided by signals, not completion.** Interviewers write observations ("stated the
  invariant unprompted", "needed a hint on the race"). The hiring decision is made from those
  notes. If a behaviour isn't observable, it doesn't exist.
- **Self-practice has no grader.** "It works" is the only feedback you get when coding alone, and
  "it works" is the *senior* bar.
- **Senior habits can read as staff, and vice versa.** More patterns, more classes and more
  features *look* senior, and often score lower (see §3).

---

## 2. How It Solves It

### Signals by dimension

| Dimension | Mid / Senior (hire) | Staff (strong hire) | Principal-ish (beyond the round) |
|---|---|---|---|
| **Requirements and scope** | Asks good questions; builds what's asked | Closed questions with defaults; writes an explicit *out of scope*; cuts scope aloud with a reason | Reframes the problem ("rate limiting won't fix aggregate overload; you need load shedding too") |
| **Modeling** | Sensible classes | Value objects with **invariants enforced in constructors**; immutable where possible; no anemic god class | Model choices tied to the evolution of the domain |
| **API** | Methods that work | Interface first; rich result types (`Decision`, not `boolean`); separates caller bugs (exception) from expected outcomes (deny) | API shaped for its consumers' failure handling (retry semantics, idempotency) |
| **Correctness and invariants** | Handles the obvious edge cases | **States the invariant before the code**; time, overflow, rounding, empty and boundary cases | Proves it: property and invariant tests |
| **Concurrency** | Makes it thread-safe (often coarse) | Names shared state, invariant and primitive, and *why not* the alternatives. Per-key, not global | Reasons about contention, fairness, and what changes on a multi-node system |
| **Extensibility (follow-ups)** | Adapts with some rework | Answers as a **delta**: new / changed / **unchanged**; v1 seams anticipated without being gold-plated | Says which follow-ups the design deliberately *won't* absorb, and why |
| **Testing** | A few happy-path tests | Fake clock; a concurrency test that would fail against the race; names tests while coding | A test strategy linked to failure modes |
| **Communication and driving** | Responds well to prompts | **Drives**: keeps the clock, narrates decisions, asks for the follow-up | Makes the interviewer's job easy; a peer-level conversation |
| **Trade-offs** | Knows one approach well | ≥ 2 alternatives with costs **and when not to use each**, numbers where possible | Quantifies ("~110 B/key → 1M keys ≈ 110 MB, estimate") and picks by context |

### The self-grading rubric (score after every mock)

Score each dimension 1–4 from *observable* behaviour only: what a silent observer would have
seen or heard.

| Dimension | 1: Below | 2: Senior | 3: Staff | 4: Strong staff |
|---|---|---|---|---|
| Scope | Started coding with no v1 list | v1 list, no out-of-scope | v1 + out-of-scope written by min 5 | ...and one scope cut made aloud, with a reason, later |
| Model | Classes changed shape mid-code | Stable classes | Invariants stated per entity | Invariants enforced (constructor/record checks) |
| API | Signature changed ≥ 2 times | Interface written, but after the implementation | Interface written first, agreed with the interviewer | Result type carries everything the caller needs; error vs outcome separated |
| Correctness | A core path is wrong | Core right, edges missed | Edges listed and handled | Boundary *values* stated (e.g. "expires at exactly W") |
| Concurrency | None, or a broken check-then-act | Correct but a global lock | Per-key + 4-line narration | ...plus why-not-X and a race test |
| Follow-ups | Rewrite, or not reached | Reworked several classes | Delta with "unchanged" named | Raised the next follow-up unprompted |
| Tests | None named | Happy path only | ≥ 4 named incl. edge/time | Fake clock + concurrency test written |
| Driving | Needed prompts to move on | Kept moving | Hit all 4 checkpoints ([Topic 01](../01-the-45-minute-script/)) | Asked for the follow-up before min 42 |
| Trade-offs | One approach, no alternatives | Alternatives named | Alternatives + costs | + when-not-to-use + a number |

**Reading the score (a heuristic of this guide, not a company formula):** mostly 3s with no 1s
reads as staff. Any **1 in Correctness or Concurrency** is usually disqualifying regardless of the
rest. 4s in Follow-ups and Driving are the clearest staff differentiators.

### Mock log template

```
Date / problem / minutes used:
Scores:  Scope _ Model _ API _ Correct _ Conc _ Follow _ Tests _ Drive _ Trade _
Checkpoint times hit:  5 __  15 __  40 __        (actual minute)
The one moment I'd replay:
Next mock, change ONE thing:
```

Change one thing per mock. Changing everything at once means you can't tell what helped.

---

## 3. The Gaps

### "Senior-looking but not staff" anti-patterns

| Anti-pattern | Why it looks good | Why it scores low | Do instead |
|---|---|---|---|
| **Pattern-stuffing**: "Factory + Strategy + Observer + Singleton" | Shows vocabulary | Patterns without a force behind them add indirection; graders read it as memorised | Introduce a pattern only when a requirement or follow-up demands it, and name that requirement ([Topic 05](../05-patterns-that-matter/)) |
| **Gold-plating**: config, metrics, persistence in v1 | Looks production-minded | Burns the time follow-ups need; nothing is finished | Name the seam ("a repository goes here") and move on |
| **Framework in a 45-min round**: Spring, DI containers, ORMs | Looks "real world" | Wiring hides the model; time is lost to boilerplate | Plain language, in-memory. Mention where the framework adapter would sit (this guide's `app/` module is that adapter, built *outside* the round) |
| **Silent coding** | Fast | No reasoning can be scored | Narrate decisions, not keystrokes |
| **Never stating invariants** | Saves 30 seconds | The grader can't tell intentional correctness from luck | One line per entity: "tokens ≤ capacity; check-and-consume atomic per key" |
| **`synchronized` everything** | Safe | Shows no reasoning about contention | Per-key locking + why-not-global |
| **Defending v1 on a breaking follow-up** | Confident | Reads as rigid | What breaks / what survives / options / pick ([Topic 01](../01-the-45-minute-script/)) |
| **Many shallow features** | Covers the prompt | Breadth without depth is the senior bar at best | One feature deep, with concurrency and tests |
| **Inheritance trees for variation** (`PremiumUserRateLimiter extends…`) | OO-looking | Rigid; a follow-up adds a dimension and explodes the tree | Composition, with data-driven policy |

### Misconceptions about the rubric itself

| Belief | Reality |
|---|---|
| "Staff = more code." | Staff = better-chosen code and more *reasoning per line*. Often less code |
| "Score 4 everywhere." | Not possible in 45 min, and trying causes gold-plating. Target 3s with no 1s |
| "Self-scores are reliable." | They inflate. Record mocks (audio is enough) and score from the recording, or swap with a peer |
| "Companies use this rubric." | They don't. Each has its own, mostly unpublished. This one is built from public prep guidance (e.g. Atlassian's "how you think" and "trade-offs") plus the problems in this guide. Use it as a practice instrument, not a prediction |

### When NOT to optimise for these signals

- **Machine-coding rounds, where running code is gated:** if it doesn't run, the rubric doesn't
  matter. Get v1 running before you polish any signal.
- **Interviewer-led rounds:** if the interviewer is clearly steering, "driving" means following
  well and adding depth, not seizing the agenda.

---

## 4. Official References

- Atlassian, *Engineering interview handbook*: "Clean code matters, but what's most important is
  how you think", and "trade-offs like verbosity versus optimization":
  https://www.atlassian.com/company/careers/resources/interviewing/engineering
- Amazon, *SDE II Interview Prep*: "scalable, robust, and well-tested code", edge cases:
  https://www.amazon.jobs/content/en/how-we-hire/sde-ii-interview-prep
- Meta, *Preparing for Your Full Loop Interview*: https://www.metacareers.com/swe-prep-onsite/
- Google, *Interview tips*: https://www.google.com/about/careers/applications/interview-tips/
- Deep-dive, not official: the staff archetypes and what "staff" means beyond interviews, in
  Will Larson's *Staff Engineer*: https://staffeng.com/

---

## 5. Key Takeaways

1. Level is decided by **observable signals**. Say invariants, trade-offs and scope cuts out loud.
2. The staff differentiators are **driving**, **follow-ups as deltas** ("unchanged" named), and
   **when-not-to-use** for every choice.
3. State the invariant **before** the lock. Narrate concurrency in four lines.
4. Score every mock 1–4 on nine dimensions. Target 3s with no 1s. A 1 in correctness or
   concurrency usually sinks the round.
5. Pattern-stuffing, gold-plating and frameworks *look* senior and cost you staff.
6. Self-scores inflate, so score from a recording. Change one thing per mock.
7. This rubric is a practice instrument, not any company's real scorecard.

---

[← Topic 01: The 45-Minute Script](../01-the-45-minute-script/) · [Index](../README.md) · [Topic 03: Domain Modeling →](../03-domain-modeling/)

**Self-grade: STRONG.** It gives a signal matrix across nine dimensions, an observable-behaviour
1–4 rubric, a mock log and an anti-pattern table with fixes, and it is honest that the rubric is
this guide's heuristic, not a company scorecard.
