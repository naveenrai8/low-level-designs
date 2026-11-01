# low-level-designs: authoring contract

Staff+ LLD interview guide. It follows `../pocs/basic.md` (numbered topics, 4-part spine, self-grade), adapted for LLD. The previous contract (Spring Boot + Swagger + PlantUML for every problem) is kept as `CLAUDE_old.md` for history and is **superseded**. Don't follow it.

## Writing rules (apply to everything: README, commands.md, uml.md, Java comments)

These come from the user's direct feedback and override any denser style elsewhere.

1. **Super simple.** Anyone should be able to follow it, not just a staff engineer.
   - Plain words and short sentences.
   - Define a term the first time it appears ("a *token* is one permission to make a request").
   - Show a small worked example (a timeline table: time → what happens → result) **before** any formula or code.
   - Say *what happens* and *why*, then the edge cases.
2. **Every method gets a comment**: public, private, constructors, and test methods.
   - Use Javadoc (`/** ... */`) that says **what it does** in plain English, plus anything surprising (what it returns, when it throws, what it changes).
   - Don't restate the code ("increments i"). Say the purpose ("gives back the tokens earned since the last refill").
   - Test methods say what behaviour they prove, in one sentence.
3. **No soft wrap.** Never break a sentence across lines in Markdown or in comments.
   - One paragraph, bullet, table row or Javadoc sentence goes on **one line**, and the editor wraps it.
   - Code itself keeps normal line breaks.

## Layout rules

- Topics are `NN-slug/` folders, numbered beginner → advanced. The slugs are fixed in `ideas.md`. Don't renumber them, because Prev/Next links depend on the numbers.
- `*_old.md` files and `_legacy-app/` are history. Never edit them. Mine them for material.
- A topic that ships code is a Maven module listed in the root `pom.xml`: `com.lld:<slug-without-number>`, package `com.lld.<short>`.

## Code rules

- **Core modules are framework-free**: Java 21 + JUnit only. No Spring, Lombok, Guava or logging frameworks in core. Only `app/` may depend on Spring Boot.
- **Interface first.** The public interface plus its error contract comes before any implementation.
- **Time is injected.**
  - `java.time.Clock` for "what time is it".
  - A `Ticker` (nanoTime) for elapsed time and timeouts.
  - A `Scheduler` seam for "later".
  - Never call `Instant.now()`, `System.currentTimeMillis()` or `Thread.sleep` in core code or in logic tests.
- **Money is `long` minor units.** Never `double`. Splits use largest remainder. Money tests use exact `assertEquals(long, long)`, never a delta.
- **Concurrency tests are deterministic.**
  - Force the interleaving with a hook/`Gap` + barrier in the broken class, or use a same-thread probe.
  - Where the race is inside the JDK, hammer behind a start-gate latch with bounded retries, and make the *fix* test assert exact totals.
  - Never rely on `sleep`.
- **Foundations use bug → fix → test-that-catches-the-bug.** The bug class is named for its defect and has its own test proving the defect.
- **Comment every method** (see Writing rules §2).
- **`app/` conventions:**
  - Only add a REST adapter when HTTP adds something to the lesson: status codes, headers like `Retry-After`, idempotency keys, or async callbacks (e.g. rate limiter, seat-hold confirm, notification webhooks). Pure in-process designs (LRU, spreadsheet, game engine) stay core-only.
  - One package per problem: `com.lld.app.<slug>`.
  - Translate domain exceptions to HTTP **at the call site**. Never add a global `@ExceptionHandler(IllegalArgumentException.class)`. That masks framework bugs as 400s (see `../pocs/SpringBoot-poc/33a-problemdetail-rfc9457-errors`).
  - Make `Clock`/`Ticker` beans so tests can freeze them.
- **Build gotchas** (see `../pocs/SpringBoot-poc/19-starters-and-bom` §3.8):
  - The root pom sets `<parameters>true</parameters>`. Without the starter-parent, unnamed `@PathVariable`s otherwise fail at runtime.
  - After any pom-only change, run `mvn clean`, because stale classes hide the fix.
  - `app/` binds `spring-boot-maven-plugin:repackage` explicitly.
- Build with `JAVA_HOME` set to a JDK 21+ and `mvn -q clean verify` from this folder.

## Doc rules (every topic README)

1. `# Topic NN: Name`, a problem hook paragraph, and a TOC.
2. **The Problem.** For problems: what's really being probed, v1 requirements, clarifying questions with assumed answers, and an explicit out-of-scope list.
3. **How It Solves It.**
   - entities + invariants
   - the interface
   - ASCII class/sequence diagrams (no PlantUML). The README keeps the whiteboard version. An optional `uml.md` companion holds formal UML in Mermaid (renders on GitHub, stays text).
   - the algorithm with its cost, **explained first with a simple worked example** (Writing rules §1)
   - the concurrency model: shared state, invariant, primitive, why
   - a code/test table linking files
4. **The Gaps.** Follow-up ladder F1–F3, each with its design delta. Then misconceptions / common mistakes, trade-offs and when-not-to, and the production/distributed delta.
5. **Official References.** Primary docs with links. Books are cited by chapter.
6. Problems also get a 45-minute timeline and a senior-vs-staff rubric. All topics get Key Takeaways, Prev · Index · Next links, and a final `**Self-grade: STRONG|ADEQUATE|THIN.**` line that states the remaining gap.
7. **Numbers are measured** (state the setup), **labelled as estimates**, or **sourced**. If a test contradicts a claim, fix the claim and say what was learned. Topic 12's M6/M7 rows came from exactly that.
8. Each code topic gets a `commands.md`: how to run it, with real output where it adds something.

## Status and grades

The master `README.md` index carries each topic's status and self-grade. Update it, and `ideas.md`, whenever a topic is written.
