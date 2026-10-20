# Low-Level Design for Staff+ Interviews

A self-paced guide to the LLD / machine-coding / code-design rounds in senior-staff+ loops (the
Atlassian Code Design round, Uber/Amazon-style machine coding, OOD inside a Google coding round).
The emphasis is what those rounds actually judge:

- the domain model and its invariants
- an interface-first API
- correct concurrency
- testability
- how you handle **follow-up requirement changes**

Framework plumbing is not the point.

- **Core modules are framework-free** (Java 21 + JUnit). That's what you'd write in the room.
- **`app/`** is the single Spring Boot module. It wraps core modules in thin REST adapters, so a
  design can be poked at over HTTP.
- **Every quantitative claim is measured** (with the setup stated), **labelled as an estimate**, or
  **sourced**.
- Every topic README follows the 4-part spine (**Problem → How It Solves It → Gaps → Official
  References**) and ends with a self-grade (STRONG / ADEQUATE / THIN).

The full planned breadth (48 topics, Phases 0–6, with a follow-up ladder per problem) is in
[`ideas.md`](ideas.md). Authoring rules are in [`CLAUDE.md`](CLAUDE.md).

## How to use it

1. Read Phase 0 once: how rounds differ, the 45-minute script, the rubric.
2. Work through Phase 1. The coded foundations (07, 08, 10, 12) each show **bug → fix → a test that
   catches the bug**. These are the mistakes that sink otherwise-good designs.
3. For each problem: read only *The Problem* section, set a 45-minute timer, and build v1 yourself.
   Then read the rest and attempt F1–F3. Grade yourself with Topic 02's rubric.

## Index

### Phase 0: Playbook

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 00 | [LLD Round Formats by Company](00-lld-round-formats/) | – | ADEQUATE |
| 01 | [The 45-Minute Script](01-the-45-minute-script/) | – | STRONG |
| 02 | [Staff Signals and the Self-Grading Rubric](02-staff-signals-and-rubric/) | – | STRONG |

### Phase 1: Foundations

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 03 | [Domain Modeling](03-domain-modeling/) | – | ADEQUATE |
| 04 | [Interface-First API and Error Modeling](04-interface-first-api-and-errors/) | – | STRONG |
| 05 | [The Patterns That Actually Matter](05-patterns-that-matter/) | – | ADEQUATE |
| 06 | [Explicit State Machines](06-state-machines/) | – | STRONG |
| 07 | [Concurrency Primitives: Atomic Check-and-Act](07-concurrency-primitives/) | ✅ 6 tests | STRONG |
| 08 | [Multi-Object Atomicity: Lock Ordering](08-multi-object-atomicity/) | ✅ 5 tests | STRONG |
| 09 | [Executors and Backpressure](09-executors-and-backpressure/) | – | STRONG |
| 10 | [Time and Testability: Clock, Ticker, Virtual Time](10-time-and-testability/) | ✅ 16 tests | STRONG |
| 11 | [Money, IDs, and Idempotency](11-money-ids-idempotency/) | – | STRONG |
| 12 | [Common Mistakes (and the Fixes That Were Also Wrong)](12-common-mistakes/) | ✅ 30 tests | STRONG |
| 13 | [From LLD to HLD: Where the Seams Go](13-lld-to-hld-seams/) | – | ADEQUATE |

### Phase 2: Classic LLD

14 parking lot · 15 vending machine · 16 elevator · 17 LRU/LFU cache · 18 logging framework ·
19 turn-based game engine · 20 library management. *All planned; see [`ideas.md`](ideas.md).*

### Phase 3: Concurrency-heavy components

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 21 | [Rate Limiter](21-rate-limiter/) **(pilot: the full template, end to end)** | ✅ 45 tests + REST in `app/` | STRONG |

22 blocking queue / thread pool · 23 delayed job scheduler · 24 KV store with TTL + transactions ·
25 pub-sub · 26 connection pool · 27 circuit breaker + retry · 28 ID generator ·
29 consistent hashing · 30 sliding-window metrics. *Planned.*

### Phase 4: Domain systems with hard invariants

31 seat-hold booking · 32 double-entry ledger · 33 Splitwise · 34 auction ·
35 order-matching engine · 36 ride/delivery dispatch. *Planned.*

### Phase 5: Design + algorithm hybrids

37 spreadsheet · 38 in-memory file system · 39 undo/redo editor · 40 autocomplete top-K ·
41 leaderboard · 42 workflow engine · 43 feature flags · 44 notification service ·
45 content popularity. *Planned.*

### Phase 6: Capstones

46 API gateway core · 47 ticketing platform · 48 Jira-lite: timed multi-round mocks. *Planned.*

Some Prev/Next links in written topics point at planned folders that don't exist yet.

## Build and run

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -q clean verify                                   # every module, every test
mvn -q -pl 21-rate-limiter test                       # one module
mvn -q -pl app -am package -DskipTests && java -jar app/target/app-1.0.0-SNAPSHOT.jar
curl -s -X POST localhost:8080/api/rate-limit/alice   # see 21-rate-limiter/commands.md
```

## Layout

```
pom.xml                 aggregator (Java 21, JUnit BOM, -parameters)
NN-topic/               README.md (+ commands.md, pom.xml, src/ when the topic ships code)
app/                    the only Spring Boot module: REST adapters over core modules
_legacy-app/            the previous single-module Spring code, untouched
*_old.md                the previous docs, untouched (graded and superseded; see 12-common-mistakes)
```

## Related guides in this workspace

- `../pocs/SystemDesign-poc`: HLD. Several prompts in `systems_old.md` belong there.
- `../pocs/Redis-poc`: the distributed rate limiter (Topic 21 F3).
- `../pocs/SpringBoot-poc`: the Spring details behind `app/`.
