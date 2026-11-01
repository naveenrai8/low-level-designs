# Topic 42: Workflow Engine

"Design Jira's workflow" sounds like an enum and a switch. The interviewer is waiting for what breaks: two people move the same issue at once, a rule changes while issues are in flight, an automation loops forever, and nobody can say who moved what.

A staff answer treats the workflow as validated data, uses a version check for races, migrates all-or-nothing, keeps automations away from the commit, and keeps an audit log of every issue change.

## Table of Contents

[0 Words](#0-words-used-in-this-topic) · [1 Problem](#1-the-problem) · [2 How It Solves It](#2-how-it-solves-it) · [3 Gaps](#3-the-gaps) · [4 References](#4-official-references) · [5 Timeline](#5-45-minute-interview-timeline) · [6 Rubric](#6-senior-vs-staff-rubric) · [7 Takeaways](#7-key-takeaways)

---

## 0. Words used in this topic

| Word | Plain meaning |
|---|---|
| **Issue** | One ticket. It is always in exactly one state. |
| **State** | A box an issue can sit in: OPEN, IN_PROGRESS, DONE. |
| **Transition** | A named move between states, for example "start" from OPEN to IN_PROGRESS. |
| **Guard** | A yes/no check that must pass before a transition runs ("the reviewer is not the author"). |
| **Workflow** | The states, the starting state and the transitions. One per project, and it can have versions. |
| **Commit** | The moment a change becomes real: the new state is stored and the audit entry is written. |
| **Version (of an issue)** | A counter that goes up by one on every commit. Used to detect races. |
| **Compare-and-set** | "Change it only if it is still the way I last saw it." Done on the version, it needs no lock while a person thinks; a loser gets a conflict and re-reads. This is the optimistic check. |
| **Hook** | An automation that runs after a commit, for example "notify the reporter". |
| **Executor** | A Java object that runs tasks for you, often on another thread. Hooks are handed to one. |
| **Idempotent** | Doing it twice has the same effect as doing it once. |
| **Audit log** | An append-only list of every issue change: who, when, from, to. |
| **Replay** | Rebuilding issue state by walking the log from empty. |
| **Migration** | Moving issues from an old workflow version to a new one using a state mapping. |
| **Differential test** | Run the same random inputs through the real code and through a slow, obviously correct model, and compare. |
| **Barrier / probe** | A hook in the code under test that lets a test stop one thread at an exact point, so a race happens on purpose. |

For how a state machine itself works (tables, State pattern, atomic illegal-move blocking) see [Topic 06](../06-state-machines/). This topic does not re-teach it.

---

## 1. The Problem

Teams want different flows (bugs need review, tasks do not) and want to change them without a deploy. Hard-coded `if` chains do not allow that. A loose "set status" API lets anyone jump an issue anywhere, lets two writers overwrite each other, and leaves no trail.

### v1 requirements (say these out loud)

- A workflow has states, one initial state, and named transitions with guards.
- `transition(issue, name, actor, expectedVersion)` moves the issue or fails with a clear error and no change.
- A project can have many workflow versions; each issue follows one version.
- Hooks run after commit. Every commit is audited.

### Clarifying questions (assumed answers)

| Question | Assumed answer |
|---|---|
| Can one transition start from several states? | Yes, a set of source states. |
| May guards call other services? | No. Pure and fast. Slow checks belong in a hook or before the call. |
| Must hooks run exactly once? | No. Here at most once; at least once needs an outbox (section 3). Make hook effects idempotent ([Topic 11](../11-money-ids-idempotency/)). |

**Out of scope:** permissions beyond guards, boards and search, durable storage, cross-issue transactions.

---

## 2. How It Solves It

### Entities and invariants

| Entity | Invariant |
|---|---|
| `WorkflowDefinition` | Built only if valid: has a declared initial state, every state in a transition exists, every transition has a source, no duplicate names, every state is reachable. Immutable after build. |
| `IssueView` | An immutable snapshot. Its version goes up by exactly 1 per commit. |
| `Guard` | Receives only a `GuardContext` (immutable issue plus actor). Purity is a contract; the engine gives a read-only view and refuses a guard that calls back into the engine. |
| Audit log | Append-only. One entry per issue create, transition or migration, written in the same lock hold as the change. |

```
 OPEN --start--> IN_PROGRESS --resolve[reviewed, not-author]--> DONE
   ^                  |                                           |
   +-----reopen-------+-------------------reopen------------------+
```

### The interface

```java
void createProject(String project, WorkflowDefinition def);
IssueView createIssue(String project, String id, Map<String,String> fields, String actor);
IssueView transition(String id, String name, String actor, long expectedVersion); // ANY_VERSION = -1 skips the check
int migrate(String project, WorkflowDefinition next, Map<String,String> stateMapping, String actor);
void addHook(String name, Hook hook);   // hook gets HookActions.transition(id, name, expectedVersion)
List<AuditEntry> auditLog();            // then Replay.rebuild(log)
```

Every deliberate error is a `WorkflowException` subtype: `InvalidWorkflow`, `IllegalTransition`, `GuardFailed` (names the guard), `VersionConflict`, `MigrationRejected`, `HookDepthExceeded`, `NotFound`, `AlreadyExists`, `InvalidRequest` (null argument), `Reentrant`, `ReplayFailed`. A failed call changes nothing.

### Worked example: validation walk (reachability)

States A, B, C, D, start A, transitions `a: A->B`, `b: B->C`. D has no way in.

| Step | Queue | Reached so far | Result |
|---|---|---|---|
| 1 | A | A | start |
| 2 | B | A, B | `a` leads to B |
| 3 | C | A, B, C | `b` leads to C |
| 4 | empty | A, B, C | D was never reached |

`build()` throws `InvalidWorkflow: state 'D' is unreachable`. Other problems (no initial state, unknown state in a transition, a transition with no source, duplicate name) are reported together, but the reachability walk needs a valid initial state, so it is skipped without one.

### Worked example: one transition, and a race

| Time | Thread A | Thread B | Result |
|---|---|---|---|
| t0 | reads I-1: OPEN, version 0 | reads I-1: OPEN, version 0 | both hold version 0 |
| t1 | `transition(.., "start", 0)` takes the lock | waits for the lock | |
| t2 | version 0 matches; "start" legal; no guards; writes IN_PROGRESS, version 1; appends audit entry; unlocks | | A wins |
| t3 | hooks go to the executor (outside the lock) | takes the lock; stored version is 1, expected 0 | B gets `VersionConflict` |

Order inside the lock matters: version check, then legality, then guards, then write. With N threads exactly 1 wins and N-1 conflict. The race test uses a move that is legal before and after (a self-loop), so only the version check can reject the losers. A second test uses a probe inside the critical section to hold thread A there and check thread B is blocked on the lock, so the interleaving is forced every run.

### Worked example: migration

Old workflow has IN_PROGRESS, the new one has ACTIVE. Mapping: `IN_PROGRESS -> ACTIVE`. Issues: I-1 OPEN, I-2 IN_PROGRESS.

| Step | What happens | Result |
|---|---|---|
| 1 | Take the lock | Nothing else commits or creates now |
| 2 | For each issue: target = mapping, or the same name; is the target a state of the new workflow? | I-1 -> OPEN ok, I-2 -> ACTIVE ok |
| 3 | Any issue whose target is missing? (with an empty mapping I-2 would land in the removed IN_PROGRESS) | Throw `MigrationRejected`, nothing changed |
| 4 | None missing: publish version 2, move every issue, one audit entry each with one shared timestamp | All moved |

Issues created during a migration wait for the lock, so they are created either before it (and migrated) or after it (on the new version). Nobody is half-moved. Honest limit: each issue is replaced on its own and `get` does not lock, so a reader can briefly see some issues moved and others not. Every single issue is always whole.

### Hooks: isolation and loops

| Step | What happens |
|---|---|
| 1 | Commit finishes, lock released |
| 2 | Each hook becomes its own task on the injected `Executor`. If the executor throws, that hook is recorded `FAILED` ("not run") and the others are still tried |
| 3 | A hook that throws anything (even an `Error`) is recorded `FAILED`; only a `VirtualMachineError` is rethrown after it is recorded |
| 4 | A hook that took longer than its budget (injected `Ticker`) is `SLOW`; exactly the budget is `OK` |
| 5 | A hook may call `actions.transition(id, name, versionItSaw)`. If a person changed the issue meanwhile it gets `VersionConflict` instead of overwriting them |

Hook depth, limit 3: the person's move is depth 0, each hook-triggered move adds 1.

| Move | Depth | Result |
|---|---|---|
| person: toB | 0 | commits; hook flips back |
| hook: toA | 1 | commits |
| hook: toB | 2 | commits |
| hook: toA | 3 | commits |
| hook: toB | 4 | `HookDepthExceeded`, recorded `FAILED`, loop ends |

Delivery here is at most once (a crash loses queued hooks). `createIssue` and `migrate` do not fire hooks. A `Ticker` can only measure slowness, not stop a hook; stopping needs a cancellable worker.

### Audit and replay

Every create, transition and migration appends `AuditEntry(at, actor, issueId, project, transition, from, to, version, workflowVersion)`. `at` comes from the injected `Clock`. Projects and workflow definitions are not logged, so replay rebuilds issue state only; the definitions must come from elsewhere.

Replay of a log with one corrupt row:

| Entry | Check | Result |
|---|---|---|
| 1. create I-1, version 0 | not seen before, version 0 | I-1 = OPEN, v0 |
| 2. start, OPEN to IN_PROGRESS, version 1 | `from` matches, version is 0+1 | I-1 = IN_PROGRESS, v1 |
| 3. reopen, from IN_PROGRESS, version 5 | version should be 2 | `ReplayFailed` |

Replay takes only the log, so it cannot run guards or hooks. Guards were decided when the entry was written, and hooks had real-world effects that already happened. This is true by construction (replay is a static function with no hook access), so no test is claimed as evidence.

### Concurrency model

One `synchronized` block guards the issue map, the audit list and the project versions, so check, write and log are one step. Single-issue reads use a `ConcurrentHashMap` of immutable snapshots and never block (`availableTransitions` locks briefly). Hooks never run while the lock is held.

`synchronized` can be re-entered by the same thread, so a guard that called the engine would commit inside a commit. The engine tracks "this thread is running guards" and throws `Reentrant`. For locks and executors see [Java concurrency guide](../../internal-pocs/Java-poc/concurrency/). For injected clocks see [Topic 10](../10-time-and-testability/).

### Code and tests

Files are in `src/main/java/com/lld/workflow/`: `WorkflowEngine` (interface), `InMemoryWorkflowEngine`, `WorkflowDefinition` with `Transition` and `Guard`, `IssueView`, the hook types, and `AuditEntry` with `Replay`. The 45 tests are in five classes (`DefinitionTest`, `TransitionTest`, `MigrationTest`, `HookTest`, `AuditReplayTest`); per-test detail and the mutation table are in `commands.md`. The differential test `engineEqualsReferenceModelAndReplay` drives 200 seeded random histories (including a rename migration) through the engine and a hand-written model that uses no engine code, then checks replay too.

---

## 3. The Gaps

### F1: per-project workflows and migration

Each project keeps a list of workflow versions; an issue records which one it follows. New issues use the latest. Migration needs a state mapping, is checked for all issues before anything moves, and is applied under one lock hold.

Production delta: do not block creates for the whole migration. Migrate lazily: the old version stays valid, each issue moves on its next write or by a background job in checked batches. The "check all" step becomes a dry run that returns the blockers. Give readers a consistent snapshot if they must never see a mix.

### F2: hooks and automations

Built: after commit, per-hook isolation, a version-checked follow-up, a depth limit. Production delta: a durable outbox. Write the event in the same database transaction as the commit and let workers deliver it with retries and a dead-letter queue (a holding area for work that kept failing), see [event-processing](../../internal-pocs/event-processing-poc/). That gives at-least-once delivery, so hook effects must be idempotent. Per-hook time limits need cancellable workers. The audit entry does not record the hook depth or cause; adding them would help debugging.

### F3: audit trail and replay

Built. Production delta: make the log the source of truth (event sourcing) and the issue map a cache, take snapshots (saved copies of the state) every N entries so replay need not start at zero, log project and definition changes too, and put a unique key on (issue, version) so a double write is rejected by the database.

### Common mistakes and myths

| Mistake or myth | Reality |
|---|---|
| "A state enum is a workflow engine" | The data must be validated, versioned and editable at runtime |
| Guards that call services or write | Retries and races produce double effects. Keep them pure |
| "Optimistic means no lock" | The commit still needs a short atomic step; it just is not held while a person thinks |
| Hook follow-up with no version | A late hook overwrites a newer human change |
| "A listed transition will succeed" | `availableTransitions` skips guards, so it can still be refused |

### Trade-offs

One global lock is simplest; at high rates shard by issue id and make migration a project-level barrier. Production also means database compare-and-set (`UPDATE ... WHERE id=? AND version=?`) and definitions stored as versioned rows with draft and publish. For a few fixed states a plain enum ([Topic 06](../06-state-machines/)) is enough.

---

## 4. Official References

- Jira Cloud, working with workflows: https://support.atlassian.com/jira-cloud-administration/docs/work-with-issue-workflows/
- Martin Fowler, Event Sourcing: https://martinfowler.com/eaaDev/EventSourcing.html
- Java 21 `AtomicReference.compareAndSet` (the same compare-and-set idea): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html
- Kleppmann, Designing Data-Intensive Applications, ch. 7 (write races) and ch. 11 (event logs).

---

## 5. 45-Minute Interview Timeline

| Minutes | What to do |
|---|---|
| 0-12 | Clarify, scope, entities, the build-time validation list |
| 12-28 | Interface and errors; code `transition` (version, legality, guards, write); draw the race table |
| 28-41 | F1 migration, F2 hooks after commit with a depth limit, F3 audit and replay |
| 41-45 | Production delta: database compare-and-set, outbox, snapshots |

---

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Definition | Map of transitions | Validated at build, immutable, reachability checked |
| Concurrency | Synchronized method | Version check, exactly-one-winner test, forced interleaving |
| Migration | Loop and update | Check all then apply; says what happens to creates and readers |
| Hooks and history | Call hooks, log lines | Isolated version-checked hooks, depth limit, replayable log, independent model |

---

## 7. Key Takeaways

1. Validate the workflow when it is built, then freeze it.
2. Guards get a read-only view and are pure by contract; re-entry is refused.
3. Version check, legality, guards, then write: one atomic step.
4. Migrate all-or-nothing: check every issue first.
5. Hooks run after commit, one failure never blocks another, follow-ups carry a version, and chains have a depth limit.
6. The audit log is written with the change; replay uses only the log.

[← Topic 41: Leaderboard](../41-leaderboard/) · [Index](../README.md) · [Topic 43: Feature Flags](../43-feature-flags/)

**Self-grade: STRONG.** Remaining gap: one process and one lock; hooks are at most once with no outbox; per-hook time limits only measure; replay rebuilds issues but not definitions; migration blocks creates instead of running lazily.
