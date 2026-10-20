# Low-Level Design: Breadth Map and Backlog

The full planned curriculum, from beginner to advanced. Every problem topic follows the template in
[`CLAUDE.md`](CLAUDE.md): a v1 that fits 45 minutes, then a **follow-up ladder** (F1 → F3) of the
requirement changes interviewers actually make. Handling that ladder is the main staff signal.

Legend: **Code** = ships a framework-free core module (+ optional REST adapter in `app/`).
**Status**: ✅ written · ⬜ planned.

---

## Phase 0: Playbook (docs only)

| # | Slug | Scope | Status |
|---|---|---|---|
| 00 | `00-lld-round-formats` | How LLD rounds differ by company (machine coding, code design, OOD-in-coding), what's judged; "verify with your recruiter" | ✅ |
| 01 | `01-the-45-minute-script` | Clarify 5 / model + API 10 / code 25 / tests + follow-ups 10, with what to say at each checkpoint | ✅ |
| 02 | `02-staff-signals-and-rubric` | Senior vs staff signals; the self-grading rubric used by every problem topic | ✅ |

## Phase 1: Foundations

| # | Slug | Scope | Code | Status |
|---|---|---|---|---|
| 03 | `03-domain-modeling` | Entities vs value objects, invariants, records/sealed/enums | – | ✅ |
| 04 | `04-interface-first-api-and-errors` | Public API first; result types vs exceptions; error taxonomy | – | ✅ |
| 05 | `05-patterns-that-matter` | Strategy, State, Observer, Factory, Decorator, Command, and when *not* to use them | – | ✅ |
| 06 | `06-state-machines` | Explicit transition tables, illegal-transition handling, enum vs sealed states | – | ✅ |
| 07 | `07-concurrency-primitives` | Picking a primitive; atomic check-and-act; 3 reproduced bugs | ✅ | ✅ |
| 08 | `08-multi-object-atomicity` | Lock ordering, half-done state, striping, per-key locks | ✅ | ✅ |
| 09 | `09-executors-and-backpressure` | Bounded queues, rejection policies, virtual threads, shutdown | – | ✅ |
| 10 | `10-time-and-testability` | `Clock` / `Ticker` / virtual-time `Scheduler`; half-open expiry; stale timers | ✅ | ✅ |
| 11 | `11-money-ids-idempotency` | `long` minor units, ID strategies, idempotency keys | – | ✅ |
| 12 | `12-common-mistakes` | 9 mistakes mined from `review-*_old.md`, and corrections to the reviews' own fixes | ✅ | ✅ |
| 13 | `13-lld-to-hld-seams` | Where the interfaces go so the design survives being distributed | – | ✅ |

Planned code for Phase 1 docs-only topics (when revisited): 06 a transition-table engine, 09 a
bounded executor with a rejection test, 11 an idempotency-key store.

---

## Phase 2: Classic LLD (re-scoped from `systems_old.md`)

| # | Slug | v1 (45 min) | F1 | F2 | F3 | Status |
|---|---|---|---|---|---|---|
| 14 | `14-parking-lot` | Multi-level lot, spot types, park/unpark, fee from an injected `Clock` | Pricing rules as Strategy (hourly, flat, weekend) | Concurrent entry gates: no double-assign | Reservations with hold expiry (reuse Topic 10) | ⬜ |
| 15 | `15-vending-machine` | Explicit State machine: idle → selecting → paid → dispensing; change-making | Coffee variant: recipes + ingredient inventory | Partial-failure refunds (dispense jam) | Remote telemetry seam | ⬜ |
| 16 | `16-elevator` | N cars, request queues, SCAN/LOOK per car | Dispatcher strategy (nearest, least-loaded, zoned) | Simulation with virtual time: wait-time p95 | Fire/maintenance modes as states | ⬜ |
| 17 | `17-lru-lfu-cache` | O(1) LRU with the correct lock (Topic 12 M2) | LFU in O(1) (frequency buckets) | TTL per entry + size-bounded by weight | Concurrency: striping trade-off vs Caffeine-style buffering | ⬜ |
| 18 | `18-logging-framework` | Levels, appenders, formatters; listener isolation | Async appender with bounded queue + drop policy | Per-logger hierarchy and config reload | Structured logs + MDC across threads | ⬜ |
| 19 | `19-turn-based-game-engine` | Tic-tac-toe N×N with O(1) win check | Snake & ladder (pluggable board, deterministic dice) | Chess move validation (sealed piece types) | Undo + replay from a move log | ⬜ |
| 20 | `20-library-management` | Catalogue, copies, loans, fines from `Clock` | Holds queue with notification | Concurrent checkout of the last copy | Multi-branch transfers | ⬜ |

## Phase 3: Concurrency-heavy components

| # | Slug | v1 (45 min) | F1 | F2 | F3 | Status |
|---|---|---|---|---|---|---|
| 21 | `21-rate-limiter` | **Pilot.** Keyed token bucket / sliding log / sliding counter; per-key `compute`; idle eviction; REST in `app/` | Tiers, config reload, compound limits | Memory bound at millions of keys | Distributed (Redis + Lua) | ✅ |
| 22 | `22-blocking-queue-thread-pool` | Bounded blocking queue (lock + 2 conditions); fixed pool on top | Rejection policies + backpressure | Graceful shutdown semantics | Work-stealing vs virtual threads discussion | ⬜ |
| 23 | `23-delayed-job-scheduler` | Schedule at/after, cancel, one-shot + fixed-rate; virtual time | Retries with backoff + jitter | Hashed timing wheel at 1M timers | Durable jobs + exactly-once-ish execution | ⬜ |
| 24 | `24-kv-store-ttl-transactions` | get/set/delete with TTL (lazy + sweep) | Nested transactions: begin/rollback/commit | Snapshot reads (MVCC-lite) | WAL + crash recovery seam | ⬜ |
| 25 | `25-pub-sub` | Topics, subscribers, per-subscriber offsets | Ack + redelivery + DLQ | Ordering per key with parallel consumers | Backpressure for slow consumers | ⬜ |
| 26 | `26-connection-pool` | Borrow/return with bounded wait; validation | Leak detection (borrow stack + timeout) | Min/max sizing + idle eviction | Health-check and failover | ⬜ |
| 27 | `27-circuit-breaker-and-retry` | Closed/open/half-open on a sliding window; virtual time | Retry with budget + jitter | Bulkhead per dependency | Per-endpoint config + metrics | ⬜ |
| 28 | `28-id-generator` | Snowflake-style 64-bit IDs; monotonic per node | Clock-step-back handling | Sortable string IDs (ULID/UUIDv7) | Node-ID assignment seam | ⬜ |
| 29 | `29-consistent-hashing` | Ring with virtual nodes; lookup O(log n) | Add/remove node and measure movement | Weighted nodes | Rendezvous hashing comparison | ⬜ |
| 30 | `30-sliding-window-metrics` | Counters + p50/p99 over the last N seconds (ring of buckets) | Mergeable sketches (HDR / t-digest discussion) | Per-key cardinality bound | Exporter seam | ⬜ |

## Phase 4: Domain systems with hard invariants

| # | Slug | v1 (45 min) | F1 | F2 | F3 | Status |
|---|---|---|---|---|---|---|
| 31 | `31-seat-hold-booking` | Unified hold-then-confirm (movie/concert/airline/hotel); half-open expiry (Topic 10) | Multi-seat atomic hold (Topic 08 ordering) | Idempotent confirm with payment callback | Overbooking policy (airline) | ⬜ |
| 32 | `32-double-entry-ledger` | Accounts + journal entries that always balance; `long` minor units | Holds/authorisations vs captures | Idempotent posting | Balance projections + audit without global locks | ⬜ |
| 33 | `33-splitwise` | Expenses, splits (equal/exact/percent, largest remainder), balances | Debt simplification (min cash flow, greedy) | Multi-currency at an injected rate | Groups + settle-up history | ⬜ |
| 34 | `34-auction` | English auction; bids, proxy bidding, close at `Clock` time | Anti-sniping extension | Concurrent bids, exactly one winner | Sealed-bid / Vickrey variant | ⬜ |
| 35 | `35-order-matching-engine` | Limit order book, price-time priority, partial fills | Market + IOC/FOK orders | Single-writer design and its throughput | Cancel/replace + event log replay | ⬜ |
| 36 | `36-ride-delivery-dispatch` | Match requests to drivers by distance (grid index) | Batching window + scoring Strategy | Driver state machine + timeouts | Surge pricing seam | ⬜ |

## Phase 5: Design + algorithm hybrids

| # | Slug | v1 (45 min) | F1 | F2 | F3 | Status |
|---|---|---|---|---|---|---|
| 37 | `37-spreadsheet` | Cells with formulas; dependency graph; recompute in topo order | Cycle detection with a useful error | Incremental recompute (dirty set) | Undo/redo of edits | ⬜ |
| 38 | `38-in-memory-file-system` | mkdir/ls/cat/write over a tree (Composite) | Paths with `..`, symlinks, permissions | Find by glob + size | Concurrency: per-directory locks | ⬜ |
| 39 | `39-undo-redo-editor` | Command pattern; undo/redo stacks | Coalescing (typing bursts) | Rope or piece table for large docs | Collaborative-editing seam (OT/CRDT pointer) | ⬜ |
| 40 | `40-autocomplete-top-k` | Trie with top-K per node | Frequency updates without full rebuild | Typo tolerance (edit distance 1) | Sharding seam | ⬜ |
| 41 | `41-leaderboard` | Update score, top-K, rank-of-user | Ties + time-windowed boards | Concurrent updates at high rate | Sorted-set mapping (Redis ZSET) | ⬜ |
| 42 | `42-workflow-engine` | Jira-like configurable states + transitions + guards | Per-project workflows + migration of in-flight issues | Hooks/automations with isolation | Audit trail + replay | ⬜ |
| 43 | `43-feature-flags` | Rules (user, %, attributes); deterministic bucketing | Targeting precedence + prerequisites | Hot reload without torn reads | Evaluation events + sampling | ⬜ |
| 44 | `44-notification-service` | Channels (email/SMS/push) as Strategy; templating | Per-user preferences + quiet hours (`Clock`, zones) | Retry + DLQ + idempotency | Rate limiting per user (reuse Topic 21) | ⬜ |
| 45 | `45-content-popularity` | Top-K tags/content over a sliding window (Atlassian classic) | Decay scoring | Bounded memory (Count-Min + heap) | Multi-tenant isolation | ⬜ |

## Phase 6: Capstones (timed multi-round mocks)

| # | Slug | Shape | Status |
|---|---|---|---|
| 46 | `46-capstone-api-gateway-core` | Routing + rate limiting (21) + circuit breaking (27) + auth hook; 3 escalating rounds with checkpoint times | ⬜ |
| 47 | `47-capstone-ticketing-platform` | Seat holds (31) + payments idempotency (11) + notifications (44) | ⬜ |
| 48 | `48-capstone-jira-lite` | Workflow engine (42) + permissions + search seam + popularity (45) | ⬜ |

---

## Mapping: the 33 prompts in `systems_old.md` → new topics

| Old prompt | New home | Note |
|---|---|---|
| Parking lot | 14 | |
| Stack Overflow | 42 (workflow) + 41 (reputation leaderboard) | Q&A CRUD alone isn't an LLD signal; the interesting parts are voting, reputation and moderation state |
| Vending Machine | 15 | |
| Logging Framework | 18 | |
| Traffic Signal Control | 06 (worked example) | A state machine with timers; too small for its own topic |
| Coffee Vending Machine | 15 (F1) | |
| Task Management System | 42 | |
| ATM | 15 (state) + 32 (ledger) | |
| LinkedIn | SystemDesign-poc; LLD slice: 41/45 | HLD prompt. LLD slice: connection-degree query, feed ranking Strategy |
| LRU Cache | 17 | |
| Tic Tac Toe | 19 | |
| Pub-Sub | 25 | |
| Elevator | 16 | |
| Car Rental | 31 (holds) + 14 (pricing Strategy) | |
| Online Auction | 34 | |
| Hotel Management | 31 | |
| Digital Wallet | 32 | |
| Airline Management | 31 (F3 overbooking) | |
| Library Management | 20 | |
| Facebook | SystemDesign-poc; LLD slice: 45 | HLD prompt |
| Restaurant Management | 31 (table holds) + 15 (order state) | |
| Concert Ticket Booking | 31 | |
| CricInfo | SystemDesign-poc; LLD slice: 25 (live score pub-sub) | HLD prompt |
| Splitwise | 33 | |
| Chess | 19 (F2) | |
| Snake and Ladder | 19 (F1) | |
| Uber | 36 | |
| University Course Registration | 31 (seats with waitlist) | |
| BookMyShow | 31 | |
| Amazon | SystemDesign-poc; LLD slice: 14-style cart + 32 + 11 | HLD prompt; Topic 12 M8 covers the cart |
| Stock Brokerage | 35 | |
| Spotify | SystemDesign-poc; LLD slice: 39 (playlist undo) / 40 | HLD prompt |
| Swiggy | 36 | |

## Foundations backlog (ideas not yet in a topic)

- Topic 08: code lock striping (currently described only).
- Topic 12: JMH comparison of RW lock vs plain lock vs Caffeine for a read-heavy map.
- Topic 10: hashed timing wheel (planned home: 23).
- Topic 07: `StampedLock` / RW lock pointers point at 12. Consider a short coded example in 07.
- Playbook: a printable one-page "follow-up ladder" cheat sheet across all problems.
