# Low-Level Design for Staff+ Interviews

A self-paced guide to the LLD / machine-coding / code-design rounds in senior-staff+ loops (the Atlassian Code Design round, Uber/Amazon-style machine coding, OOD inside a Google coding round). The emphasis is what those rounds actually judge:

- the domain model and its invariants
- an interface-first API
- correct concurrency
- testability
- how you handle **follow-up requirement changes**

Framework plumbing is not the point.

- **Core modules are framework-free** (Java 21 + JUnit). That's what you'd write in the room.
- **`app/`** is the single Spring Boot module. It wraps core modules in thin REST adapters, so a design can be poked at over HTTP.
- **Every quantitative claim is measured** (with the setup stated), **labelled as an estimate**, or **sourced**.
- Every topic README follows the 4-part spine (**Problem → How It Solves It → Gaps → Official References**) and ends with a self-grade (STRONG / ADEQUATE / THIN).

The full planned breadth (48 topics, Phases 0–6, with a follow-up ladder per problem) is in [`ideas.md`](ideas.md). Authoring rules are in [`CLAUDE.md`](CLAUDE.md).

## How to use it

1. Read Phase 0 once: how rounds differ, the 45-minute script, the rubric.
2. Work through Phase 1. The coded foundations (07, 08, 10, 12) each show **bug → fix → a test that catches the bug**. These are the mistakes that sink otherwise-good designs.
3. For each problem: read only *The Problem* section, set a 45-minute timer, and build v1 yourself. Then read the rest and attempt F1–F3. Grade yourself with Topic 02's rubric.

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

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 14 | [Parking Lot](14-parking-lot/) | ✅ 22 tests | STRONG |
| 15 | [Vending Machine](15-vending-machine/) | ✅ 39 tests | STRONG |
| 16 | [Elevator](16-elevator/) | ✅ 39 tests | STRONG |
| 17 | [LRU / LFU Cache](17-lru-lfu-cache/) | ✅ 71 tests | STRONG |
| 18 | [Logging Framework](18-logging-framework/) | ✅ 53 tests | STRONG |
| 19 | [Turn-Based Games](19-turn-based-game-engine/): three standalone modules, no shared engine | | STRONG |
| | ↳ [Tic-tac-toe](19-turn-based-game-engine/tic-tac-toe/) | ✅ 58 tests (3 opt-in) | STRONG |
| | ↳ [Snake and ladder](19-turn-based-game-engine/snake-and-ladder/) | ✅ 82 tests | STRONG |
| | ↳ [Chess](19-turn-based-game-engine/chess/) (full rules, perft-verified) | ✅ 142 tests | STRONG |
| 20 | [Library Management](20-library-management/) | ✅ 37 tests | STRONG |

### Phase 3: Concurrency-heavy components

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 21 | [Rate Limiter](21-rate-limiter/) **(pilot: the full template, end to end)** | ✅ 45 tests + REST in `app/` | STRONG |
| 22 | [Blocking Queue and Thread Pool](22-blocking-queue-thread-pool/) | ✅ 38 tests | STRONG |
| 23 | [Delayed Job Scheduler](23-delayed-job-scheduler/) | ✅ 89 tests (heap + timing wheel, measured to 1M timers) | STRONG |
| 24 | [KV Store with TTL and Transactions](24-kv-store-ttl-transactions/) | ✅ 48 tests | STRONG |
| 25 | [Pub-Sub](25-pub-sub/) | ✅ 33 tests | STRONG |
| 26 | [Connection Pool](26-connection-pool/) | ✅ 32 tests (per-loan leases, forced lost-wake-up + close-order races) | STRONG |
| 27 | [Circuit Breaker and Retry](27-circuit-breaker-and-retry/) | ✅ 54 tests (half-open max-wait, forced probe races, retry budget + jitter, bulkhead) | STRONG |
| 28 | [ID Generator](28-id-generator/) | ✅ 53 tests (Snowflake + UUIDv7 + ULID, spec vectors) | STRONG |
| 29 | [Consistent Hashing](29-consistent-hashing/) | ✅ 48 tests (ring + rendezvous, measured movement + vnode spread, Murmur3 vectors) | STRONG |
| 30 | [Sliding-Window Metrics](30-sliding-window-metrics/) | ✅ 54 tests (epoch ring, log-linear histogram ≤0.78% measured, mergeable, capped keys, Prometheus export) | STRONG |

### Phase 4: Domain systems with hard invariants

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 31 | [Seat-Hold Booking](31-seat-hold-booking/) | ✅ 37 tests + 13 REST in `app/` (ordered multi-seat locks, per-key idempotent confirm 201/200/409/410, refund notes, clock-step guard) | STRONG |
| 32 | [Double-Entry Ledger](32-double-entry-ledger/) | ✅ 54 tests (zero-sum drafts, holds with lazy expiry + retry-safe capture, idempotent posting, sorted account locks, consistent live trial balance) | STRONG |
| 33 | [Splitwise](33-splitwise/) | ✅ 39 tests (largest-remainder splits, greedy vs exact bitmask-DP settle-up, FX at settle-up, append-only history) | STRONG |
| 34 | [Auction](34-auction/) | ✅ 43 tests (proxy bidding, reserve lift, overflow-safe caps, soft close, clock read inside the lock, sealed-bid Vickrey) | STRONG |
| 35 | [Order-Matching Engine](35-order-matching-engine/) | ✅ 46 tests + opt-in benchmark (price-time priority, IOC/FOK, overflow-free FOK pre-check, single-writer engine with interrupt-safe close, Cboe-sourced cancel/replace priority, deterministic replay) | STRONG |
| 36 | [Ride/Delivery Dispatch](36-ride-delivery-dispatch/) | ✅ 66 tests (grid index with exhaustive tight stop bound, differential vs brute force, batching + Hungarian, driver state machine with offer timeouts, cancel/expiry, surge seam, measured light/balanced/overloaded sim) | STRONG |

### Phase 5: Design + algorithm hybrids

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 37 | [Spreadsheet](37-spreadsheet/) | ✅ 44 tests (43 default; parser with depth limit, shortest-cycle error, BFS dirty set + Kahn recompute, 3000-seed differential, undo/redo, measured incremental vs full) | STRONG |
| 38 | [In-Memory File System](38-in-memory-file-system/) | ✅ 70 tests (Composite tree, `..` clamped at root, symlink loop limit, Linux trailing-slash-on-symlink rule, rename permission and loop checks under a tree lock, park-after-resolve race tests, glob on code points, find by size) | STRONG |
| 39 | [Undo/Redo Editor](39-undo-redo-editor/) | ✅ 47 tests (46 default; invertible commands, validate-before-change, coalescing with injected ticker, capped history, piece table vs naive string differential, OT insert/delete transform, measured piece table vs flat list) | STRONG |
| 40 | [Autocomplete Top-K](40-autocomplete-top-k/) | ✅ 37 tests (36 default; trie with cached top-K per node, targeted repair on update/remove, edit-distance-1 fuzzy with banded walk, read-write lock wrapper with per-method lock probes, prefix-routed shards, brute-force differential, measured 10k/100k/1M) | STRONG |
| 41 | [Leaderboard](41-leaderboard/) | ✅ 43 tests (treap with subtree sizes for O(log n) rank, node-visit bounds, deterministic score/time/id ties + 1224 rank, naive-model differential, calendar-windowed boards with stale-write refusal and DST, read-write lock with per-method lock probes, Redis ZSET codec with the real reverse-name tie rule) | STRONG |
| 42 | [Workflow Engine](42-workflow-engine/) | ✅ 45 tests (validated immutable definitions, pure guards with re-entry refused, version compare-and-set with forced interleavings, all-or-nothing migration, isolated versioned hooks with depth limit, audit log + replay vs an independent reference model) | STRONG |
| 43 | [Feature Flags](43-feature-flags/) | ✅ 50 tests (Murmur3 bucketing with known-answer vectors and measured chi-square spread, LaunchDarkly-order precedence with off prerequisites unmet, cycle and depth limits, typed rule conditions, immutable snapshot hot reload with forced torn-read and lost-CAS tests, bounded event buffer with hash-based sampling) | STRONG |
| 44 | [Notification Service](44-notification-service/) | ✅ 53 tests + 6 REST (channels as Strategy with escaping per channel, quiet hours across zones and DST gaps/overlaps, retry with capped seeded jitter + DLQ, idempotent submit with 202/200/422, crash-safe send loop, per-user rate limit reusing Topic 21, submit-order FIFO) | STRONG |
| 45 | [Content Popularity](45-content-popularity/) | ✅ 53 tests (exact sliding-window top-K with bucket rotation, Count-Min + heap sketch window with measured Zipf error vs exact and a witness for its by-design miss, exponential-decay scoring with rebase and bounded weights, multi-tenant isolation with a forced-race tenant cap, same-thread lock probes) | STRONG |

### Phase 6: Capstones

| # | Topic | Code | Self-grade |
|---|---|---|---|
| 46 | [API Gateway Core](46-capstone-api-gateway-core/) | ✅ 79 tests + 10 REST (path-first longest-prefix routing with 405, RFC 3986 path cleaning that routes and forwards the same path, API-key + scope auth chain that strips credentials, per-client limiter reusing Topic 21, retry around a per-upstream breaker reusing Topic 27 with deadline, retry budget and honest last answer, body cap, 3 timed rounds) | STRONG |
| 47 | [Ticketing Platform](47-capstone-ticketing-platform/) | ✅ 54 tests (checkout saga over Topics 31 + 32 + 44 with one record per idempotency key, a 12-point crash matrix with resume, compensation vs refund vs needs-review, 409 in-flight / 422 key conflict via tryLock, outbox notification, sweep that survives a bad record, 3 timed rounds) | STRONG |
| 48 | [Jira-lite](48-capstone-jira-lite/) | ✅ 63 tests (glue over Topics 42 + 45: BROWSE-first permission order with 404-to-hide, role scheme with record keys, inverted-index search filtered by an allow-list, outbox-style index marks that survive a change during the write, read-your-writes vs lag with deletes hidden at once, migration re-index, per-project trending that drops dead ids before the top-K cut, 3 timed rounds) | STRONG |

## Build and run

```bash
export JAVA_HOME=/path/to/jdk-21
mvn -q clean verify                                   # every module, every test
mvn -q -pl 21-rate-limiter test                       # one module
mvn -q -pl 19-turn-based-game-engine/chess test        # one game (or -pl 19-turn-based-game-engine for all three)
mvn -q -pl 19-turn-based-game-engine/chess test -Dperft.deep=true   # opt-in deep perft (~15 s)
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
