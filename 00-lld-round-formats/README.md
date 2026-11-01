# Topic 00: LLD Round Formats by Company

Imagine your recruiter tells you: "Your next interview is an LLD round." That one name can mean very different interviews:

- 🏗️ You build a working parking lot in 90 minutes.
- 💬 You talk through an object model for 45 minutes.
- 🔧 You write code, and then the interviewer adds new requirements to see if your design can handle them.
- 🚫 Or the company has no LLD round at all.

If you prepare for the wrong one, you lose. Some people show up with Spring Boot and PlantUML when the interviewer wanted plain code. Others practise class diagrams for an interview that never asks for one.

So your first job is to find out **which round you are in**. Ask your recruiter. Don't trust an old forum post (for example, one from 2021).

Let's see the four kinds of round, what each company does, and what can go wrong.

> **Read every company row below as "commonly reported, check with your recruiter".** Formats change often. They also differ by team, level and region. When an official company page says something, this topic quotes it and links to it. Everything else is marked *reported*.

**Words used in this topic:**

- *LLD* (low-level design) means designing the classes, methods and rules of one small system.
- *Machine coding* is a round where your code must actually run.
- A *follow-up* is a new requirement the interviewer adds after your first version works, such as "now support two limits per user".
- *v1* is your first working version, before any follow-ups.
- An *invariant* is a fact that must always be true, such as "free spots never go below zero".

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How to Find and Prepare for Your Round](#2-how-to-find-and-prepare-for-your-round)
   - [2.1 The four kinds of LLD round](#21-the-four-kinds-of-lld-round)
   - [2.2 Company by company](#22-company-by-company)
   - [2.3 What rounds A and B expect](#23-what-rounds-a-and-b-expect)
   - [2.4 What "plain code, in memory" looks like](#24-what-plain-code-in-memory-looks-like)
   - [2.5 How to show your code works](#25-how-to-show-your-code-works)
   - [2.6 What a follow-up looks like](#26-what-a-follow-up-looks-like)
   - [2.7 Questions to ask your recruiter](#27-questions-to-ask-your-recruiter)
   - [2.8 Which topics of this guide to study](#28-which-topics-of-this-guide-to-study)
3. [Common Mistakes and Things to Watch Out For](#3-common-mistakes-and-things-to-watch-out-for)
4. [When Should We Skip LLD Preparation?](#4-when-should-we-skip-lld-preparation)
5. [Official References](#5-official-references)
6. [Key Takeaways](#6-key-takeaways)

---

## 1. The Problem

### 1.1 What goes wrong when you prepare for the wrong round

| You prepared for… | …but the round was | What goes wrong |
|---|---|---|
| 📐 Class diagrams and design patterns | Machine coding, where the code must run | At minute 30 your diagram is done, but nothing runs. You fail. |
| ⚡ Writing running code fast | Code design with follow-ups | Your v1 works, but the first follow-up forces you to rewrite it. |
| 🧩 An LLD round | No LLD round at all (only coding and system design) | You spent weeks practising the wrong skill. |
| 🏢 A service built on a big framework | A plain-code exercise | You lose 15 minutes on setup, and the framework hides the design the interviewer wants to see. |

### 1.2 What every LLD round checks

The format changes from company to company. But the things the interviewer grades stay mostly the same:

1. 🧱 **Modeling:** you pick the right things to model, and you say what must always be true about each one.
2. 🔌 **Interface:** you give a public API that someone can use without reading your code.
3. ✅ **Correctness:** you handle edge cases and errors. Where it matters, you also handle many threads calling your code at the same time (concurrency).
4. 🧩 **Extensibility:** a new requirement needs a small change, not a rewrite.
5. ⚖️ **Trade-offs:** you name the other options and say why you didn't pick them.
6. 🗣️ **Communication:** you lead the session, explain your choices, and cut scope on purpose when time is short.

---

## 2. How to Find and Prepare for Your Round

### 2.1 The four kinds of LLD round

Two questions sort every LLD round into one of four kinds:

- Must your code actually run?
- Do follow-ups decide your grade?

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

In plain words:

- **A. Machine coding:** you build a first version, show it running, and then extend it.
- **B. Code design (also called OOD, object-oriented design):** you design the model and the API, write the core code, and then handle follow-ups.
- **C. Practical or integration:** you work on a realistic codebase or API, often with some debugging.
- **D. Object-model discussion:** you mostly talk and sketch on a whiteboard.

💡 **Important:** your preparation depends on the kind. A and B reward code that changes easily. C rewards reading unfamiliar code fast. D rewards clear talking about the model.

### 2.2 Company by company

Check every row with your recruiter. Quotes in "double quotes" come from the company's official page, linked in [Official References](#5-official-references).

| Company | Is there an LLD round? | Kind | What the company says, or what people report | Framework? |
|---|---|---|---|---|
| **Atlassian** | **Yes, officially.** One coding part is called "Data Structures and Code Design". | B | Official: "Clean code matters, but what's most important is how you think". Interviewers look at "trade-offs like verbosity versus optimization". "You can choose the language". "Missing a line isn't a deal-breaker". *Reported:* follow-ups that grow the problem, for example a popularity or top-K tracker, or a rate limiter. | None. Their page says the round "isn't about following language-specific patterns in a particular code base". |
| **Amazon** | Not as a separate round. Coding and design are different interviews in the loop. | (B)/D | Official (SDE II prep page): "write syntactically correct code — no pseudo code", "scalable, robust, and well-tested code", "Expect at least one question on software systems design". The loop is "four 55-minute interviews". *Reported:* object-design questions (like a parking lot) still show up in some loops. | None |
| **Uber, Flipkart, Swiggy and similar (machine coding is common in India)** | *Reported:* yes, a "machine coding" round. | A | *Reported:* about 90 to 120 minutes. You build a working system that keeps its data in memory (no database, no UI), show it running, and then extend it. You are graded on running code, clean modules and tests. | None. Any language, data in memory. |
| **Google** | *Reported:* usually **no** LLD round at senior or staff level. The rounds are coding, system design and behavioural. | (D) | *Reported:* object design sometimes comes up inside a coding or design interview. Staff loops focus on system design. | n/a |
| **Meta** | *Reported:* **no** LLD round. The rounds are coding, system design or **product architecture** (APIs and data models), plus behavioural. | (D) | Official prep page: the loop "will consist of several different conversations". The details are in Meta's Full Loop guide PDF. Product architecture is the closest thing to LLD: you discuss entities, APIs and how data flows. | n/a |
| **Netflix** | *Reported:* it depends **on the team**. There is no format for the whole company. | C/B | *Reported:* practical coding and a design talk close to the team's real work. Culture fit (based on the culture memo) counts a lot. | Depends on the team |
| **Stripe** | *Reported:* practical rounds instead of LLD. | C | *Reported:* integration (you work against a realistic API or codebase), debugging and design rounds. There is no verified official page, so treat this as hearsay. | Often your own environment |

### 2.3 What rounds A and B expect

| Topic | What is expected |
|---|---|
| 💻 Language | The plain language you are fastest in. **No framework.** |
| 🗄️ Storage | Keep data in memory (lists and maps). Say where a database would plug in, but don't build it. |
| ⏱️ Time | *Typically* about 45 to 60 minutes for B. *Reported* 90 to 120 minutes for A. |
| ▶️ Running code | A: required, and you show it running. B: preferred, so at least the main paths should work. |
| 🧪 Tests | A: a `main` method or unit tests. B: name the test cases, and write 2 or 3 if you have time. |
| 🔁 Follow-ups | The main signal for staff level in both. Expect 1 to 3 changed requirements. |

### 2.4 What "plain code, in memory" looks like

In rounds A and B, start with the public interface. Write it before any class that does the work. A caller should understand it without reading your code.

Here is the interface that this guide's rate limiter starts with. From [RateLimiter.java](../21-rate-limiter/src/main/java/com/lld/ratelimiter/RateLimiter.java):

```java
public interface RateLimiter {

    /** Asks for 1 permit; the same as {@code tryAcquire(key, 1)}. */
    default Decision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /**
     * Checks and takes {@code permits} for {@code key} in one step.
     *
     * <p>It is all or nothing: either every permit is taken (allowed) or none are (denied). It never takes only some of them.
     *
     * @throws IllegalArgumentException if {@code key} is blank, {@code permits < 1}, or {@code permits} is more than the limit (that request could never succeed)
     */
    Decision tryAcquire(String key, int permits);
}
```

Notice three things:

1. ✅ It is plain Java. There is no framework, no database and no web server.
2. ✅ The comment states the rule ("all or nothing") and the errors. The caller doesn't need to read the code that does the work.
3. ✅ The algorithm is hidden behind the interface. You can swap it later without changing any caller.

💡 **Important:** in a 45-minute round, an interface like this is often worth more than a long class diagram. It shows modeling, the API and the error rules in a few lines.

### 2.5 How to show your code works

In round A you must show your code running. In round B, a few tests are a strong signal. Each test should prove one rule.

From [RateLimiterContractTest.java](../21-rate-limiter/src/test/java/com/lld/ratelimiter/RateLimiterContractTest.java):

```java
    /** Proves 8 requests at the same instant get exactly 5 permits with a limit of 5. */
    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void burstAtOneInstantGrantsExactlyTheLimit(Algorithm algorithm) {
        RateLimiter limiter = RateLimiterFactory.create(algorithm, FIVE_PER_SECOND, ticker);

        int allowed = 0;
        for (int i = 0; i < 8; i++) {
            if (limiter.tryAcquire("alice").allowed()) {
                allowed++;
            }
        }

        assertEquals(5, allowed);
    }
```

Run it from the `low-level-designs/` folder (with `JAVA_HOME` set to JDK 21 or newer):

```bash
mvn -pl 21-rate-limiter test -Dtest=RateLimiterContractTest
```

Output (on this run with JDK 25, showing only the summary lines):

```text
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.084 s -- in com.lld.ratelimiter.RateLimiterContractTest
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Notice three things:

1. ✅ The test name says the rule in plain words: a burst gets exactly the limit.
2. ✅ The test uses a fake clock (`ticker`), so it never sleeps and always gives the same result.
3. ✅ The same test runs once for every algorithm (`@EnumSource`), so all of them must follow the same rule.

💡 **Important:** if time is short, say the test names out loud and write two or three. Naming the cases already shows you know where the bugs hide.

### 2.6 What a follow-up looks like

In rounds A and B, the follow-ups decide your level. Here is a real one from [Topic 21](../21-rate-limiter/):

> **F1:** "Free users get 10/min, paid get 1000/min, and ops wants to change limits without a deploy."

A good v1 makes this a small change. You add one new piece that picks the limit for each user, and the old code stays the same. A packed v1 with everything hard-coded needs a rewrite.

💡 **Important:** when you design v1, keep asking yourself "what will they ask next?" Leave room for that change.

### 2.7 Questions to ask your recruiter

Copy and paste these:

1. "Is there a round focused on object-oriented / low-level design, or is design only at system level?"
2. "Is code expected to compile and run? In my own IDE or a shared editor?"
3. "How long is it, and are follow-up extensions part of it?"
4. "Any language restrictions? Can I use the standard library's concurrency utilities?"
5. "At this level, what distinguishes a strong hire in that round?"

### 2.8 Which topics of this guide to study

| Your round | What to spend time on |
|---|---|
| A. Machine coding | Topics [01](../01-the-45-minute-script/), 03–06, then the Phase 2 problems, timed from start to finish. |
| B. Code design | Topics 01, 02, 04 (interface first), then the Phase 3 and Phase 5 problems **with their follow-ups**. |
| C. Practical | Foundations 07–12 (concurrency, time, money, common mistakes). Practise reading unfamiliar code fast. |
| D. Discussion only | Topics 03, 05, 06 and 13. For loops that are mostly system design, use [SystemDesign-poc](../../pocs/SystemDesign-poc/). |

---

## 3. Common Mistakes and Things to Watch Out For

### 3.1 Do all FAANG companies have an LLD round?

❌ No.

*Reported:* Google and Meta usually don't have one at staff level. Atlassian officially does. Amazon mixes object design into its normal loop.

So **ask**. Don't assume.

### 3.2 Is LLD just UML and design patterns?

❌ No.

Interviewers want working code that is easy to extend, and they want you to explain your trade-offs.

If you name a pattern without a reason, it counts against you (see [Topic 02](../02-staff-signals-and-rubric/)).

### 3.3 Does using Spring or Django make you look ready for production?

❌ No.

In 45 to 60 minutes, setting up a framework eats your time. It also hides the design that the interviewer is grading.

Atlassian's page says its round "isn't about following language-specific patterns in a particular code base".

### 3.4 If you finish v1, do you pass?

❌ No.

In rounds A and B, the follow-ups decide your level.

A big v1 that needs a rewrite for the first follow-up scores *lower* than a small v1 that extends cleanly.

### 3.5 Does a staff-level answer need more classes?

❌ No.

A staff-level answer has fewer classes. Each class has a clear reason to exist. The rules (invariants) are stated out loud. And scope is cut on purpose.

### 3.6 Is memorising the list of common questions enough?

❌ No.

The same questions come up again and again (rate limiter, parking lot, LRU cache, file system, top-K).

But a memorised answer won't help you when the follow-up changes the problem. The skill that carries over is handling follow-ups.

### 3.7 Can you make up the time if you prepared for the wrong kind of round?

❌ No.

If you draw diagrams for 30 minutes in a machine-coding round, you can't get that time back. The code must run, and now there is too little time to write it.

### 3.8 Can you trust interview reports on Glassdoor or LeetCode Discuss?

⚠️ Only partly.

Each post describes one team at one moment. Formats change.

Trust official company pages and your recruiter more than any forum post.

### 3.9 Is the interview the same at every level?

❌ No.

One company can run different interviews for senior and staff candidates. Ask about the loop for *your* level.

---

## 4. When Should We Skip LLD Preparation?

| Situation | Better choice |
|---|---|
| 🎯 Your recruiter confirms the loop is only coding plus system design, with no object-design part | Spend your time on system design ([SystemDesign-poc](../../pocs/SystemDesign-poc/)). |
| 🛟 You skip LLD, but a coding round might drift into design | Still keep the Phase 1 basics (concurrency and modeling). |

---

## 5. Official References

- Atlassian — [Engineering interview handbook](https://www.atlassian.com/company/careers/resources/interviewing/engineering) (Data Structures and Code Design, Systems Design, Manager, Values)
- Atlassian — [Interviewing resource hub](https://www.atlassian.com/company/careers/resources/interviewing)
- Amazon — [SDE II Interview Prep](https://www.amazon.jobs/content/en/how-we-hire/sde-ii-interview-prep) (no pseudo code, systems design, four 55-minute interviews)
- Amazon — [How We Hire](https://www.amazon.jobs/content/en/how-we-hire)
- Meta — [Preparing for Your Full Loop Interview](https://www.metacareers.com/swe-prep-onsite/) (links to the Full Loop guide PDF)
- Google — [Interview tips](https://www.google.com/about/careers/applications/interview-tips/)
- Netflix — [Culture Memo](https://jobs.netflix.com/culture) (counts a lot in Netflix interviews)

---

## 6. Key Takeaways

1. 🧭 "LLD round" can mean four different kinds of interview: machine coding, code design, practical, and discussion. Prepare for the one you have.
2. 📜 Atlassian officially has a Code Design part. Amazon officially asks for real, tested code and a design question. Google and Meta *reportedly* have no separate LLD round at staff level.
3. ☎️ Check every row of any format table with your recruiter, including the table in this topic.
4. 💻 Don't use a framework. Write plain code and keep data in memory. Say where a database or another service would plug in, but don't build it.
5. 🔁 Follow-ups decide your level. Make v1 easy to change instead of packing it with features.
6. ❓ Ask your recruiter the five questions above before you decide what to practise.

---

[Index](../README.md) · [Topic 01: The 45-Minute Script →](../01-the-45-minute-script/)

**Self-grade: ADEQUATE.** The official facts for Atlassian, Amazon and Meta are quoted and linked. But the Google, Netflix, Stripe and machine-coding rows can only be *reported*, not checked against an official page, so this topic can't be STRONG on hard facts.
