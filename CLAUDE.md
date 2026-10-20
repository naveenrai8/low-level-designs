# low-level-designs: authoring contract

Staff+ LLD interview guide. It follows `../pocs/basic.md` (numbered topics, 4-part spine, self-grade),
adapted for LLD. The previous contract (Spring Boot + Swagger + PlantUML for every problem) is kept
as `CLAUDE_old.md` for history and is **superseded**. Don't follow it.

## Layout rules

- Topics are `NN-slug/` folders, numbered beginner → advanced. The slugs are fixed in `ideas.md`.
  Don't renumber them, because Prev/Next links depend on the numbers.
- `*_old.md` files and `_legacy-app/` are history. Never edit them. Mine them for material.
- A topic that ships code is a Maven module listed in the root `pom.xml`:
  `com.lld:<slug-without-number>`, package `com.lld.<short>`.

## Code rules

- **Core modules are framework-free**: Java 21 + JUnit only. No Spring, Lombok, Guava or logging
  frameworks in core. Only `app/` may depend on Spring Boot.
- **Interface first.** The public interface plus its error contract comes before any
  implementation.
- **Time is injected.** Use `java.time.Clock` for "what time is it", a `Ticker` (nanoTime) for
  elapsed time and timeouts, and a `Scheduler` seam for "later". Never call `Instant.now()`,
  `System.currentTimeMillis()` or `Thread.sleep` in core code or in logic tests.
- **Money is `long` minor units.** Never `double`. Splits use largest remainder. Money tests use
  exact `assertEquals(long, long)`, never a delta.
- **Concurrency tests are deterministic.** Force the interleaving with a hook/`Gap` + barrier
  in the broken class, or use a same-thread probe. Where the race is inside the JDK, hammer behind
  a start-gate latch with bounded retries, and make the *fix* test assert exact totals. Never
  rely on `sleep`.
- **Foundations use bug → fix → test-that-catches-the-bug.** The bug class is named for its defect
  and has its own test proving the defect.
- **`app/` conventions:**
  - One package per problem: `com.lld.app.<slug>`.
  - Translate domain exceptions to HTTP **at the call site**. Never add a global
    `@ExceptionHandler(IllegalArgumentException.class)`. That masks framework bugs as 400s (see
    `../pocs/SpringBoot-poc/33a-problemdetail-rfc9457-errors`).
  - Make `Clock`/`Ticker` beans so tests can freeze them.
- **Build gotchas** (see `../pocs/SpringBoot-poc/19-starters-and-bom` §3.8):
  - The root pom sets `<parameters>true</parameters>`. Without the starter-parent, unnamed
    `@PathVariable`s otherwise fail at runtime.
  - After any pom-only change, run `mvn clean`, because stale classes hide the fix.
  - `app/` binds `spring-boot-maven-plugin:repackage` explicitly.
- Build with `JAVA_HOME` set to a JDK 21+ and `mvn -q clean verify` from this folder.

## Doc rules (every topic README)

1. `# Topic NN: Name`, a problem hook paragraph, and a TOC.
2. **The Problem.** For problems: what's really being probed, v1 requirements, clarifying
   questions with assumed answers, and an explicit out-of-scope list.
3. **How It Solves It.**
   - entities + invariants
   - the interface
   - ASCII class/sequence diagrams (no PlantUML)
   - the algorithm with its cost
   - the concurrency model: shared state, invariant, primitive, why
   - a code/test table linking files
4. **The Gaps.** Follow-up ladder F1–F3, each with its design delta. Then misconceptions /
   common mistakes, trade-offs and when-not-to, and the production/distributed delta.
5. **Official References.** Primary docs with links. Books are cited by chapter.
6. Problems also get a 45-minute timeline and a senior-vs-staff rubric. All topics get Key
   Takeaways, Prev · Index · Next links, and a final `**Self-grade: STRONG|ADEQUATE|THIN.**` line
   that states the remaining gap.
7. **Numbers are measured** (state the setup), **labelled as estimates**, or **sourced**. If a test
   contradicts a claim, fix the claim and say what was learned. Topic 12's M6/M7 rows came from
   exactly that.
8. Each code topic gets a `commands.md`: how to run it, with real output where it adds something.

## Status and grades

The master `README.md` index carries each topic's status and self-grade. Update it, and
`ideas.md`, whenever a topic is written.
