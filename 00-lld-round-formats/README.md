# Topic 00: LLD Round Formats by Company

"LLD round" means four different interviews depending on who is asking. You might be asked to
build a working parking lot in 90 minutes, to discuss an object model for 45, to write
extensible code that a follow-up will break, or to do no LLD at all. Candidates who prepare for
the wrong one show up with Spring Boot and PlantUML when they're expected to build something in a
plain language, or they grind class diagrams for a loop that never asks for one. The first job
is to find out **which round you're actually in**, and to ask your recruiter rather than trust a
forum post from 2021.

> **Read every row below as "commonly reported, verify with your recruiter".** Formats change
> often and vary by team, level and region. Where an official page states something, it's
> quoted and linked. Everything else is labelled *reported*.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [Key Takeaways](#5-key-takeaways)

---

## 1. The Problem

### What breaks if you don't know the format

| You prepared for… | …but the round was | What goes wrong |
|---|---|---|
| Class diagrams + patterns | Machine coding (must run) | Diagram done at minute 30, no running code, fail |
| Running code fast | Code design with follow-ups | v1 works, but F1 needs a rewrite. "Not extensible" |
| An LLD round | No LLD round (coding + system design) | Weeks spent on the wrong skill |
| Framework-heavy service | Plain-language exercise | 15 min lost on wiring. The interviewer can't see the domain model |

### What every LLD-style round actually evaluates

Across companies, the stable core of the grading is the same, even when the format differs:

1. **Modeling:** the right entities and value objects, with invariants stated.
2. **Interface:** a public API that a caller could use without reading the code.
3. **Correctness:** edge cases, error handling, and concurrency where it's relevant.
4. **Extensibility:** a requirement change becomes a *design delta*, not a rewrite.
5. **Trade-offs:** you name the alternatives and say why you didn't pick them.
6. **Communication:** you drive, you narrate, and you cut scope deliberately.

---

## 2. How It Solves It

### The four round archetypes

```
                 must the code run?
                 yes                         no / partially
             +---------------------------+------------------------------+
 follow-ups  | A. Machine coding         | B. Code design / OOD         |
 drive the   |   (build v1, demo, extend)|   (model + API + core code,  |
 grade?      |                           |    then follow-ups)          |
             +---------------------------+------------------------------+
 no, single  | C. Practical / integration| D. Object-model discussion   |
 problem     |   (real-ish codebase, API,|   (whiteboard, mostly        |
             |    debugging)             |    talk + sketch)            |
             +---------------------------+------------------------------+
```

### Company by company (verify every row with your recruiter)

| Company | Is there a dedicated LLD round? | Archetype | What is said / reported | Framework? |
|---|---|---|---|---|
| **Atlassian** | **Yes, officially.** The coding interview has two parts: "Data Structures and Code Design" | B | Official: "Clean code matters, but what's most important is how you think". Interviewers look at "trade-offs like verbosity versus optimization". "You can choose the language". "Missing a line isn't a deal-breaker". *Reported:* follow-ups that extend the problem, e.g. popularity/top-K trackers or rate limiters | None. Their page says it "isn't about following language-specific patterns in a particular code base" |
| **Amazon** | Not an LLD round as such. Coding and design are separate loop interviews | (B)/D | Official (SDE II prep): "write syntactically correct code — no pseudo code", "scalable, robust, and well-tested code", "Expect at least one question on software systems design". The loop is "four 55-minute interviews". *Reported:* OOD prompts (parking lot style) still appear in some loops | None |
| **Uber, Flipkart, Swiggy and similar (India-heavy machine coding)** | *Reported:* yes, a "machine coding" round | A | *Reported:* ~90–120 min, build a working in-memory system (no DB/UI), demo it, then an extension. Graded on running code, modularity, tests | None; any language, in-memory |
| **Google** | *Reported:* usually **no** dedicated LLD round at senior/staff. Coding + system design (+ behavioural) | (D) | *Reported:* OOD occasionally appears inside a coding or design interview. Staff loops weight system design heavily | n/a |
| **Meta** | *Reported:* **no** LLD round. Coding, system design or **product architecture** (API/data-model-centric), behavioural | (D) | Official prep page: the loop "will consist of several different conversations". Details are in Meta's Full Loop guide PDF. Product architecture is the closest relative of LLD: entities, APIs, data flow | n/a |
| **Netflix** | *Reported:* varies **by team**; no company-wide format | C/B | *Reported:* practical coding plus design discussion close to the team's real work. Culture fit (the culture memo) is weighted heavily | Team-dependent |
| **Stripe** | *Reported:* practical rounds rather than LLD | C | *Reported:* integration (work against a real-ish API/codebase), bug-fix/debugging, and design rounds. No verified official page, so treat it all as hearsay | Your own environment, often |

### Expectations common to archetypes A and B

| Dimension | Expectation |
|---|---|
| Language | Plain language you're fastest in (Java/Python/Go/Kotlin/C++/TS). **No framework.** |
| Storage | In-memory collections. Mention where the DB seam would go, but don't build it |
| Time box | *Typically* ~45–60 min for B; *reported* 90–120 min for A |
| Running code | A: mandatory, demoed. B: strongly preferred; at least core paths compile in your head |
| Tests | A: a driver/main or unit tests. B: name the test cases, write 2–3 if time allows |
| Follow-ups | The main staff signal in both. Expect 1–3 requirement changes |

### Questions to ask your recruiter (copy-paste)

1. "Is there a round focused on object-oriented / low-level design, or is design only at system level?"
2. "Is code expected to compile and run? In my own IDE or a shared editor?"
3. "How long is it, and are follow-up extensions part of it?"
4. "Any language restrictions? Can I use the standard library's concurrency utilities?"
5. "At this level, what distinguishes a strong hire in that round?"

### Map this guide onto the round you have

| Your round | Spend time on |
|---|---|
| A. Machine coding | Topics [01](../01-the-45-minute-script/), 03–06, then Phase 2 problems timed end to end |
| B. Code design | 01, 02, 04 (interface first), Phase 3 and 5 problems **with their follow-ups** |
| C. Practical | 07–12 foundations (concurrency, time, money, mistakes); read unfamiliar code fast |
| D. Discussion only | 03, 05, 06, 13; for HLD-heavy loops use [SystemDesign-poc](../../pocs/SystemDesign-poc/) |

---

## 3. The Gaps

### Misconceptions

| Belief | Reality |
|---|---|
| "FAANG all have an LLD round." | *Reported:* Google and Meta generally don't at staff level. Atlassian officially does. Amazon folds OOD into its loop. **Ask.** |
| "LLD = UML + design patterns." | Graders weight working, extensible code and trade-offs. A pattern named without a reason is a negative signal ([Topic 02](../02-staff-signals-and-rubric/)) |
| "Use Spring/Django to look production-ready." | In 45–60 min, framework wiring hides the model the interviewer is grading. Their page says Atlassian's round "isn't about following language-specific patterns in a particular code base" |
| "Finishing v1 = pass." | In archetypes A and B, the follow-ups are where the level is decided. A rigid v1 that needs a rewrite at F1 scores *below* a smaller v1 that extends cleanly |
| "Staff LLD = more classes." | Staff = fewer, better-justified abstractions, named invariants, and deliberate scope cuts |
| "The prompt list is the curriculum." | Prompts recur (rate limiter, parking lot, LRU, file system, top-K), but the *follow-up skill* transfers, not the memorised answer |

### Failure modes

- **Wrong archetype:** you diagram for 30 minutes in a must-run machine-coding round. You can't
  recover that time.
- **Over-trusting reports:** formats on Glassdoor/LeetCode Discuss are snapshots of one team at
  one time. Weight official pages and your recruiter's answer above them.
- **Ignoring level:** the same company can run different loops at senior and at staff. Ask
  about *your* level.

### When NOT to prepare for LLD

If your recruiter confirms a coding + system design loop with no OOD component, spend the time on
system design ([SystemDesign-poc](../../pocs/SystemDesign-poc/)). Keep only the Phase 1
foundations (concurrency, modeling) as a backstop for coding rounds that drift into design.

---

## 4. Official References

- Atlassian, *Engineering interview handbook* (Data Structures + Code Design, Systems Design,
  Manager, Values): https://www.atlassian.com/company/careers/resources/interviewing/engineering
- Atlassian, *Interviewing resource hub*: https://www.atlassian.com/company/careers/resources/interviewing
- Amazon, *SDE II Interview Prep* (no pseudo code, systems design, four 55-min interviews):
  https://www.amazon.jobs/content/en/how-we-hire/sde-ii-interview-prep
- Amazon, *How We Hire*: https://www.amazon.jobs/en/landing_pages/interviewing-at-amazon
- Meta, *Preparing for Your Full Loop Interview* (links the Full Loop guide PDF): https://www.metacareers.com/swe-prep-onsite/
- Google, *Interview tips*: https://www.google.com/about/careers/applications/interview-tips/
- Netflix, *Culture Memo* (heavily weighted in Netflix loops): https://jobs.netflix.com/culture

---

## 5. Key Takeaways

1. "LLD round" covers four archetypes: machine coding, code design, practical, and discussion. Prepare for yours.
2. Atlassian officially has a Code Design part. Amazon officially requires real, tested code and
   a design question. Google and Meta *reportedly* have no dedicated LLD round at staff.
3. Every row in any format table, including this one, is "verify with your recruiter".
4. No framework. Use a plain language, in-memory, with seams named but not built.
5. Follow-ups decide the level. Optimise v1 for **extensibility under change**, not feature count.
6. Ask the recruiter the five questions above before choosing what to practise.

---

[Index](../README.md) · [Topic 01: The 45-Minute Script →](../01-the-45-minute-script/)

**Self-grade: ADEQUATE.** Official facts are quoted and linked for Atlassian, Amazon and Meta,
but the Google, Netflix, Stripe and machine-coding rows are by nature *reported*, not verifiable,
so this topic can't be STRONG on hard facts.
