# Topic 02: Staff Signals and the Self-Grading Rubric

Two candidates hand in the same working rate limiter. One is rated senior and the other staff. The difference is rarely the code. The staff candidate said the rule before writing the lock. They cut scope out loud. They raised the many-servers problem before being asked. They answered the follow-up by saying which classes *do not* change. If you cannot see these signals, you cannot practise them. A mock you cannot grade teaches nothing. This topic names the signals and gives you a rubric to score yourself after every mock.

**Words used here.** A *signal* is something the interviewer can see or hear you do. An *invariant* is a rule that must always be true, such as "tokens never exceed capacity". *Gold-plating* means adding extras nobody asked for. A *design delta* is what is new, changed and unchanged when a requirement changes.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

- **Level is decided by signals, not by finishing.** Interviewers write notes like "stated the invariant unprompted" or "needed a hint on the race". The hiring decision comes from those notes. If the interviewer cannot see a behaviour, it does not count.
- **Solo practice has no grader.** When you code alone, "it works" is your only feedback. "It works" is only the *senior* bar.
- **Some habits look staff but are not.** More patterns, more classes and more features look impressive. They often score lower (see §3).

---

## 2. How It Solves It

### Signals by dimension

| Dimension | Mid / Senior (hire) | Staff (strong hire) | Principal-ish (beyond the round) |
|---|---|---|---|
| **Requirements and scope** | Asks good questions; builds what is asked | Asks yes/no questions with defaults; writes an *out of scope* list; cuts scope aloud with a reason | Reframes the problem ("rate limiting won't fix overall overload; you need load shedding too") |
| **Modeling** | Sensible classes | Small value types whose **rules are checked in the constructor**; unchangeable objects where possible; no one giant class | Model choices that fit how the domain will grow |
| **API** | Methods that work | Interface first; a rich result (`Decision`, not `boolean`); caller bugs throw, expected outcomes (deny) are values | API shaped for how callers handle failure (retries, safe repeats) |
| **Correctness and invariants** | Handles the obvious edge cases | **Says the rule before the code**; covers time, overflow, rounding, empty and boundary cases | Proves it with tests of the rule itself |
| **Concurrency** | Makes it thread-safe (often with one big lock) | Names the shared state, the rule and the tool, and says *why not* the alternatives. One lock per key, not one global lock | Reasons about waiting, fairness, and what changes across many servers |
| **Extensibility (follow-ups)** | Adapts with some rework | Answers as a **delta**: new / changed / **unchanged**; leaves room for change without over-building | Says which follow-ups the design *will not* absorb, and why |
| **Testing** | A few happy-path tests | Fake clock; a thread test that would fail if the race existed; names tests while coding | A test plan tied to the ways it can fail |
| **Communication and driving** | Answers prompts well | **Leads**: watches the clock, explains decisions, asks for the follow-up | Makes the interviewer's job easy; talks as a peer |
| **Trade-offs** | Knows one approach well | At least 2 alternatives with costs **and when not to use each**, with numbers where possible | Gives numbers ("about 110 B per key, so 1M keys is about 110 MB, estimate") and picks by context |

### The self-grading rubric (score after every mock)

Score each dimension from 1 to 4. Use only what a silent observer would have seen or heard.

| Dimension | 1: Below | 2: Senior | 3: Staff | 4: Strong staff |
|---|---|---|---|---|
| Scope | Started coding with no v1 list | v1 list, no out-of-scope | v1 and out-of-scope written by minute 5 | ...and later cut one feature aloud, with a reason |
| Model | Classes changed shape mid-code | Stable classes | A rule stated for each class | Rules enforced in code (constructor checks) |
| API | Signature changed 2 or more times | Interface written after the code | Interface written first and agreed | Result holds everything the caller needs; errors and outcomes kept apart |
| Correctness | A main path is wrong | Main path right, edges missed | Edges listed and handled | Exact boundary values stated (e.g. "expires at exactly W") |
| Concurrency | None, or a broken check-then-act | Correct but one global lock | One lock per key, explained in 4 lines | ...plus why not the alternatives, and a race test |
| Follow-ups | Rewrite, or not reached | Reworked several classes | Delta with "unchanged" named | Raised the next follow-up unasked |
| Tests | None named | Happy path only | 4 or more named, incl. edge and time cases | Fake clock and thread test written |
| Driving | Needed prompts to move on | Kept moving | Hit all 4 checkpoints ([Topic 01](../01-the-45-minute-script/)) | Asked for the follow-up before minute 42 |
| Trade-offs | One approach, no alternatives | Alternatives named | Alternatives and costs | Plus when not to use each, and a number |

**Reading the score (this guide's rule of thumb, not a company formula):** mostly 3s and no 1s reads as staff. A **1 in Correctness or Concurrency** usually sinks the round, whatever else you scored. 4s in Follow-ups and Driving are the clearest staff signs.

**Example score card:** you write the interface first and the interviewer agrees (API 3), you name four tests but write none (Tests 3), you use one global lock (Concurrency 2), and you reach the follow-up at minute 44 (Driving 2). Result: mostly 3s but two 2s, so next mock you change one thing, the concurrency narration.

### Mock log template

```
Date / problem / minutes used:
Scores:  Scope _ Model _ API _ Correct _ Conc _ Follow _ Tests _ Drive _ Trade _
Checkpoint times hit:  5 __  15 __  40 __        (actual minute)
The one moment I'd replay:
Next mock, change ONE thing:
```

Change one thing per mock. If you change everything at once, you cannot tell what helped.

---

## 3. The Gaps

### "Senior-looking but not staff" anti-patterns

| Anti-pattern | Why it looks good | Why it scores low | Do instead |
|---|---|---|---|
| **Pattern-stuffing**: "Factory + Strategy + Observer + Singleton" | Shows vocabulary | A pattern with no need behind it adds extra layers; graders read it as memorised | Add a pattern only when a requirement needs it, and name that requirement ([Topic 05](../05-patterns-that-matter/)) |
| **Gold-plating**: config, metrics, storage in v1 | Looks production-minded | Burns the time follow-ups need; nothing is finished | Name the plug-in point ("a repository goes here") and move on |
| **Framework in a 45-minute round**: Spring, DI containers, ORMs | Looks "real world" | Setup hides the model and wastes time | Use plain code and in-memory data. Say where the framework would sit (this guide's `app/` module is that, built *outside* the round) |
| **Silent coding** | Fast | No reasoning can be scored | Explain decisions, not keystrokes |
| **Never stating the rules** | Saves 30 seconds | The grader cannot tell care from luck | One line per type: "tokens never exceed capacity; check and spend happen in one step per key" |
| **`synchronized` everything** | Safe | Shows no thought about threads waiting on each other | One lock per key, and say why not one global lock |
| **Defending v1 against a breaking follow-up** | Confident | Looks rigid | Say what breaks, what survives, the options, your pick ([Topic 01](../01-the-45-minute-script/)) |
| **Many shallow features** | Covers the prompt | Breadth without depth is the senior bar at best | One feature done deep, with thread safety and tests |
| **Subclass trees for variation** (`PremiumUserRateLimiter extends…`) | Looks object-oriented | Rigid; one new follow-up multiplies the subclasses | Combine small parts, and drive the limits from data |

### Misconceptions about the rubric itself

| Belief | Reality |
|---|---|
| "Staff = more code." | Staff = better-chosen code and more *reasoning per line*. Often less code |
| "Score 4 everywhere." | Not possible in 45 minutes, and trying causes gold-plating. Aim for 3s and no 1s |
| "Self-scores are reliable." | They run high. Record the mock (audio is enough) and score from it, or swap with a peer |
| "Companies use this rubric." | They do not. Each has its own, mostly unpublished. This one is built from public prep advice (e.g. Atlassian's "how you think" and "trade-offs") plus the problems in this guide. Use it to practise, not to predict |

### When NOT to optimise for these signals

- **Machine-coding rounds, where the code must run:** if it does not run, the rubric does not matter. Get v1 running before you polish any signal.
- **Interviewer-led rounds:** if the interviewer is steering, "leading" means following well and adding depth. Do not grab the agenda.

---

## 4. Official References

- Atlassian, *Engineering interview handbook*: "Clean code matters, but what's most important is how you think", and "trade-offs like verbosity versus optimization": https://www.atlassian.com/company/careers/resources/interviewing/engineering
- Amazon, *SDE II Interview Prep*: "scalable, robust, and well-tested code", edge cases: https://www.amazon.jobs/content/en/how-we-hire/sde-ii-interview-prep
- Meta, *Preparing for Your Full Loop Interview*: https://www.metacareers.com/swe-prep-onsite/
- Google, *Interview tips*: https://www.google.com/about/careers/applications/interview-tips/
- Deep-dive, not official: the staff archetypes and what "staff" means beyond interviews, in Will Larson's *Staff Engineer*: https://staffeng.com/

---

## 5. Key Takeaways

1. Level is decided by **observable signals**. Say invariants, trade-offs and scope cuts out loud.
2. Staff is set apart by **leading the session**, **follow-ups as deltas** ("unchanged" named), and **saying when not to use** each choice.
3. Say the rule **before** the lock. Explain thread safety in four lines.
4. Score every mock 1 to 4 on nine dimensions. Aim for 3s and no 1s. A 1 in correctness or concurrency usually sinks the round.
5. Pattern-stuffing, gold-plating and frameworks *look* senior and cost you staff.
6. Self-scores run high, so score from a recording. Change one thing per mock.
7. This rubric is a practice instrument, not any company's real scorecard.

---

[← Topic 01: The 45-Minute Script](../01-the-45-minute-script/) · [Index](../README.md) · [Topic 03: Domain Modeling →](../03-domain-modeling/)

**Self-grade: STRONG.** It gives a signal matrix across nine dimensions, an observable-behaviour 1–4 rubric, a mock log and an anti-pattern table with fixes, and it is honest that the rubric is this guide's heuristic, not a company scorecard.
