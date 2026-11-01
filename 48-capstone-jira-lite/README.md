# Topic 48: Capstone, Jira-lite

"Design a small Jira." People create issues, move them through a workflow, search for them and see what is trending. The hard part is not any one feature. It is that every path (move, search, trending, comment, bulk) must ask "may this person?" and that the search copy and the trending copy must never disagree with the real data. This capstone glues two finished topics (the workflow engine and the popularity tracker) to a permission check and a search index.

It is a timed mock interview in three rounds. The rounds are the timeline.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It (Rounds 1 to 3)](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Timeline](#5-45-minute-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

## 1. The Problem

What is really probed: "can you make sure nobody sees or changes what they should not, on every path, including the copies?" A *copy* here means the search index and the trending counters. They are built from the real data, so they can be stale, or can leak.

v1: projects hold issues (key like `WEB-1`). An issue has a title, an assignee, labels, comments and a status. The status follows a workflow ([Topic 42](../42-workflow-engine/)). People hold a *role* in a project, and the project's *permission scheme* says which *actions* each role allows. Search finds issues by project, status, assignee, label and text. Trending shows the hottest issues and labels ([Topic 45](../45-content-popularity/)).

Clarifying questions, with the assumed answers:
- Can someone have different roles in different projects? Yes. Roles are per project.
- What if you may not see an issue? You get the same "not found" as for a key that does not exist, so nobody can probe which keys exist.
- Is search instant after a write? It is a choice. We offer both and say which one we promise.
- What does "move to another project" do? The issue gets a new key in the target, restarts at the target's first state, and the old key is deleted.
- Out of scope: REST, custom fields, real Lucene, ranking by relevance, notifications (Topic 44), real login, stemming (search matches whole words only).

## 2. How It Solves It

Reused, not rebuilt: [Topic 42](../42-workflow-engine/) owns status, transitions, guards, versions and hooks. [Topic 45](../45-content-popularity/) gives `WindowedTopK`, a "top items in the last N minutes" counter. [Topic 6](../06-state-machines/) explains the state machine idea and [Topic 10](../10-time-and-testability/) explains the injected clock. The new code is a handful of small files in `com.lld.jiralite`.

Entities and invariants:

| Thing | What it holds | Invariant |
|---|---|---|
| Permission scheme | rows (project, role, action) and (project, user, role) | No row means no. A grant in one project says nothing about another |
| Issue (engine, Topic 42) | status, version, workflow version | Changes only through a legal transition; a refused one changes nothing |
| Issue (human fields, ours) | title, reporter, assignee, labels, comments, deleted flag | Changed only after the permission check passed |
| Search index (copy) | word to ids, plus status, assignee, labels | Written only from committed state; never holds a deleted id for long; never returns a project outside the allow-list |
| Dirty set (outbox) | ids whose copy is out of date | An id leaves only before its write starts, and comes back if the write fails |
| Tracker (copy), one per project | event counts in a time window | Reads are filtered against live issues |

```
 JiraService implements Jira   (one lock; each method: 1 BROWSE?  2 action right?  3 do it)
   |-- PermissionScheme  <|-- RoleBasedPermissionScheme       (Role x Action per project)
   |-- WorkflowEngine (42)         owns status; hook "jira-index" marks dirty
   |-- dirty set -----drain()----> SearchIndex <|-- InvertedIndex     (word -> ids)
   `-- Map<project, WindowedTopK (45)>    trending reads: all counts -> drop dead ids -> first k
```

The interface (interface first) and its error contract:

```java
public interface Jira {
    IssueDetails createIssue(String user, String project, String title, String assignee, Set<String> labels);
    IssueDetails get(String user, String issueId);                       // counts a view
    IssueDetails transition(String user, String issueId, String name, long expectedVersion);
    Map<String, Outcome> bulkTransition(String user, List<String> ids, String name);   // DONE | DENIED | REFUSED | ERROR
    void comment(String user, String id, String text);   void assign(String user, String id, String assignee);
    void delete(String user, String id);   IssueDetails move(String user, String id, String targetProject);
    int migrate(String user, String project, WorkflowDefinition next, Map<String, String> stateMapping);
    List<String> search(String user, String status, String assignee, String label, String text);
    void refresh();   List<Ranked> trendingIssues(String user, String project, int k);   List<Ranked> trendingLabels(...);
}
```

Errors: `IssueNotFound` (missing, deleted or not browsable, all the same), `PermissionDenied` (you can see it but lack the right; also unknown or hidden project names in create and trending), `ProjectNotFound` (allowed, but never created), the engine's own `WorkflowException`s (nothing changed), `IndexUnavailable` (search refuses; message is generic, the key and cause are only in `getCause()`).

### Round 1 (0 to 15 min): issues, workflow, permissions

Prompt: "Create projects and issues, and move issues along a workflow. Only the right people may do each thing."

A strong candidate says: first the words. A *role* is a named bundle (VIEWER, DEVELOPER, ADMIN). An *action* is one thing you may try (BROWSE, CREATE, TRANSITION, COMMENT, ADMINISTER). The *scheme* is a table keyed by (project, role, action), and the rule is *deny by default*: no row means no. Then the order of checks, always the same: can you see it (BROWSE), may you do this (the action), and only then the workflow's own rules (state, guard, version). Create and move-target also need BROWSE, so there is no exception to "see first". Hiding comes first, so a stranger gets "not found" and never learns that the issue exists (HTTP allows this: RFC 9110 says a server may answer 404 to hide a forbidden resource). Last, the scheme is an interface (`PermissionScheme`), so a company can plug in its own.

A timeline of one move (the permission check sits before the engine):

| Step | Who | What happens | Result |
|---|---|---|---|
| 1 | bob (developer) | `transition(WEB-1, start)` | BROWSE ok, TRANSITION ok, engine moves OPEN to IN_PROGRESS |
| 2 | bob | `transition(WEB-1, done)` | allowed to try, but the guard "reporter cannot close own issue" says no, state unchanged |
| 3 | carol (viewer) | `transition(WEB-1, done)` | BROWSE ok, TRANSITION missing, `PermissionDenied`, engine never called |
| 4 | eve (nobody) | `transition(WEB-1, done)` | BROWSE missing, `IssueNotFound` (same text as a made-up key) |

Code delta: `Action`, `Role`, `PermissionScheme`, `RoleBasedPermissionScheme`, `Jira`, `JiraService` (create, transition, comment, assign, delete, bulk). Proving tests: `RoundOneTest` (`createNeedsTheCreateRight`, `permissionIsCheckedBeforeTheWorkflow`, `strangerCannotTellRealFromMissing`, `guardRefusalChangesNothing`, `bulkChecksEveryIssue`, `schemeDeniesByDefaultAndPerProject`) and `HardeningTest` (`deniedAssignChangesNothing`, `hiddenIssuesAreNotFoundOnEveryWritePath`, `bulkContinuesAfterAnUnexpectedError`).

### Round 2 (15 to 30 min): the search seam

Prompt: "Add search by project, status, assignee, label and text. Users must only find what they may browse. What do you promise about freshness?"

A strong candidate says: an *inverted index* is a table from each word to the set of issues that contain it (a book's index at the back). A text search looks up each word and *intersects* the sets, so it touches only the candidates of the words. A query with no text has no word to start from and scans every document, and the allow-list is applied to the candidates inside the index. A real engine turns the allow-list into a filter clause or picks shards first. The index sits behind `SearchIndex` (put, remove, search), so Lucene or Elasticsearch can replace it. Words are runs of letters and digits in any language; a text with no words matches nothing (not everything).

Two rules keep the copy honest. Rule A: the index is written *only from committed state*. The service marks an issue *dirty* (a tiny outbox: "this changed"), and `drain()` removes the mark, reads the issue back from the engine and writes it. Removing the mark first matters: a change that lands during the write re-adds its mark and is not lost (`changeDuringAnIndexWriteIsNotLost`). A failed write puts the mark back. A refused move never marks anything, so a guard failure cannot reach the index. Rule B: the permission filter is not applied after the search. The service builds the *allow-list* of projects the user may browse and passes it into the query. An empty list means "nothing", never "everything".

The promise, stated and tested. With `readYourWrites` on, a search drains first, so a search after your write always sees it (the searcher pays). Elasticsearch offers the same idea the other way round: `refresh=wait_for` makes the writer wait (see References). With it off, search may lag until `refresh()`; Elasticsearch by default refreshes about every second. One thing is always immediate: a deleted or moved key is dropped from search results at once, because search filters ids against live issues. If the indexer is down, search throws `IndexUnavailable` instead of returning stale data.

| Step | Action | Index holds | Search "crash" says |
|---|---|---|---|
| 1 | create WEB-1 "Login page crash" (lag mode) | nothing, 1 dirty | nothing |
| 2 | `refresh()` | WEB-1 | WEB-1 |
| 3 | start WEB-1 | WEB-1 as OPEN, 1 dirty | filter IN_PROGRESS: nothing |
| 4 | `refresh()` | WEB-1 as IN_PROGRESS | filter IN_PROGRESS: WEB-1 |

Code delta: `SearchIndex`, `InvertedIndex`, `IssueDoc`, `Query`, the dirty set, `drain()`, the engine hook `jira-index`, and `migrate` (42 fires no hook for a migration, so the service marks the project itself). Proving tests (`RoundTwoTest`): `filtersWork`, `searchIsPermissionFiltered`, `readYourWritesWhenPromised`, `lagUntilRefreshWhenNotPromised`, `failedTransitionNeverReachesTheIndex`, `indexerOutageIsRetried`, the differential test `indexMatchesBruteForce`. `HardeningTest` adds `emptyTextQueryMatchesNothing`, `indexMatchesAnIndependentOracle` (a hand-written tokenizer, so the index is not its own judge), `migrationRefreshesTheIndex`, `lagModeDeleteIsInvisibleAtOnce` and `outageMessageLeaksNothing`.

### Round 3 (30 to 45 min): trending issues and labels

Prompt: "Show the most active issues and labels per project. Make sure moved or deleted issues do not linger and nothing leaks between projects."

A strong candidate says: one `WindowedTopK` per project (a tracker that counts events in the last N time buckets and forgets older ones). Separate trackers mean *tenant isolation* (one customer's data cannot mix with another's) by construction: WEB traffic cannot land in OPS. A view and a successful transition each count as one event. Then the honest limit: the tracker cannot forget one id, so a deleted or moved issue would stay in the counters until its events age out. The fix is to filter at read time against live data. The order matters: ask the tracker for *all* counts, drop dead ids, and only then take the first k. Taking k first returns too few rows after the leader is deleted (the "short top-K" trap). Trending labels are derived from the live issues (each adds its count to its labels), also before the cut. Output needs BROWSE. A user who cannot see an issue cannot view it, so cannot push it up the chart.

| Step | Event | WEB trending | OPS trending |
|---|---|---|---|
| 1 | alice views WEB-1 five times | WEB-1: 5 | empty |
| 2 | alice moves WEB-1 to OPS (new key OPS-2) | empty (WEB-1 is gone) | empty (OPS-2 starts fresh) |
| 3 | dave views OPS-2 twice | empty | OPS-2: 2 |
| 4 | 10 minutes pass | empty | empty |

Code delta: one tracker per project, `trendingIssues`, `trendingLabels`, `liveRanked` and `move`. Proving tests (`RoundThreeTest`): `projectsAreIsolated`, `trendingIsPermissionFiltered`, `deniedViewsAreNotCounted`, `labelsAddUp`, `deletedIssueLeavesTrending`, `movedIssueDoesNotLinger`, `moveNeedsBothRights`, `trendingForgetsOldViews`; and `HardeningTest`: `trendingFiltersBeforeTakingTheFirstK`, `trendingLabelsSumBeforeTakingTheFirstK`, `moveNeedsAdministerInTheSource`, `moveNeedsCreateInTheTarget`, `ghostProjectIsNotFoundAndBurnsNoKey`.

### End-to-end test

`EndToEndTest.theWholeStory` is one script: a viewer is refused create, a bug moves along, a guard refuses and the index is untouched, search lags and then is exact, strangers see nothing, a bulk call gives `{WEB-2: REFUSED, WEB-1: DONE}`, trending is per project, the hot issue moves projects and vanishes from the old one, and after 10 virtual minutes the window is empty. Every output is asserted exactly.

### Concurrency model and costs

Shared state: the human fields, the key counters and the trackers. Invariant: a permission check and the change after it see the same state. Primitive: one `synchronized` lock on the service. The dirty set is a thread-safe set outside that lock, because the engine hook may mark from another thread. Honest cost: index writes (and same-thread hooks) run while the lock is held, which with a real network index would be I/O under a global lock, and the trending read sorts all live ids under it. The fix, which `drain()` already allows because it removes the mark before it reads, is to take a snapshot under the lock and write outside it. Costs: a text search is the size of the smallest word's id set plus the filters; a no-text search is O(documents); a trending read is O(D log D) for D tracked ids; `drain()` is O(dirty). Tests use `Runnable::run` hooks and one manual clock, and force the interleaving with a same-thread probe, so nothing sleeps.

### Code and tests

| File | What it is |
|---|---|
| `Jira.java`, `JiraService.java` | The interface with its error contract, and the front door |
| `PermissionScheme.java`, `RoleBasedPermissionScheme.java`, `Role.java`, `Action.java` | The deny-by-default seam and its in-memory table |
| `SearchIndex.java`, `InvertedIndex.java`, `IssueDoc.java`, `Query.java` | The search seam and the word-to-ids index |
| `IssueDetails.java`, `Outcome.java`, `JiraException.java` | Results and errors |
| `RoundOneTest`, `RoundTwoTest`, `RoundThreeTest`, `HardeningTest`, `EndToEndTest`, `Rig` | 63 tests (12, 12, 11, 27, 1) and the shared set-up |

## 3. The Gaps

Follow-up ladder:
- **F1: "A hook fails or the indexer is down."** Delta: marks are removed only before a write and put back on failure, so a failure leaves the issue pending (`indexerOutageIsRetried`). The `jira-index` hook can be replaced by a hook of the same name and its failures only show in the engine's hook results, so every method also marks dirty itself. Tests `transitionIsIndexedWithoutTheHook`, `commentIsIndexedWithoutTheHook`, `assignIsIndexedWithoutTheHook`, `deleteIsIndexedWithoutTheHook` and `moveIsIndexedWithoutTheHook` swap the hook for a do-nothing one and still pass.
- **F2: "Permissions change while someone is searching."** Delta: the allow-list is computed per search call from the live scheme, so a revoked role takes effect on the next search. Nothing is cached per user. A real system caches and needs an invalidation story (state the staleness window).
- **F3: "Millions of issues."** Delta (design only, not coded): shard the index by project, so the allow-list picks shards; feed it from a durable log (an outbox table or *CDC*, change data capture: reading the database's own change log) instead of an in-process set; per-project trackers become keys in Redis or a stream processor (see [Topic 45](../45-content-popularity/)); snapshot under the lock and write outside it.

Misconceptions and common mistakes:
- "Filter the results after the search." That leaks counts, paging and timing, and wastes work. Pass the allow-list in.
- "Return 403 for a hidden issue." That confirms the issue exists. Answer "not found" for hidden and missing alike.
- "Bulk is just a loop, so it is safe." It is safe only because each item goes through the single-item path (mutant M7).
- "Index inside the transition." Then a refused or failed move could already be indexed. Index from committed state, after the commit.
- "Take the top k from the tracker, then drop dead ids." You get fewer than k rows (mutants S1, S2).
- "The engine tells me about every change." Not migrations: 42 fires no hook for them, and its hooks run after the commit and can fail without undoing it.
- A deleted or moved issue stays known to the engine (42 has no delete), so a later migration still considers it. Documented, not fixed.

Trade-offs and when not to: read-your-writes makes search a little slower; lag mode is faster but users may not find what they just made. One global lock is fine for a teaching core and wrong for a busy server. A role table is simple. Atlassian has separate Move, Delete and Assign/Edit permissions; here ADMINISTER stands in for Move and Delete, and TRANSITION for Assign. Filters rule: status and assignee match exactly (case-sensitive), labels are lower-cased on both sides, text matches whole words.

Production delta: a durable outbox, an index service with its own failures and a rebuild-from-scratch job, permission checks as index filters, per-tenant limits on trackers (Topic 45 has a multi-tenant wrapper), and an audit of denials. `pendingIndexWrites()` takes no user, so it is for operations and tests only.

## 4. Official References

- Elasticsearch, [Near real-time search](https://www.elastic.co/guide/en/elasticsearch/reference/current/near-real-time.html), [Refresh API](https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-refresh.html) (about a 1 s refresh, only for indices searched in the last 30 s) and [the `refresh` parameter](https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-refresh.html) (`wait_for`: the writer waits).
- Apache Lucene, [project home](https://lucene.apache.org/core/) (the inverted index that Elasticsearch is built on).
- IETF, [RFC 9110 section 15.5.4, 403 Forbidden](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.4) (a server that wishes to hide a forbidden resource may answer 404).
- OWASP, [Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) (deny by default, check on every request).
- microservices.io, [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html).
- Atlassian, [Managing project permissions](https://confluence.atlassian.com/adminjiraserver/managing-project-permissions-938847145.html) (the real schema this topic shrinks).
- Reused topics: [42 workflow engine](../42-workflow-engine/), [45 content popularity](../45-content-popularity/), [06 state machines](../06-state-machines/), [10 time and testability](../10-time-and-testability/).

## 5. 45-Minute Timeline

| Time | Do | Say out loud |
|---|---|---|
| 0 to 3 | Ask the clarifying questions | "Roles are per project. Hidden means not found." |
| 3 to 10 | Interface first: `PermissionScheme`, then `Jira` | "Deny by default. Order: see, may do, workflow rules." |
| 10 to 15 | Round 1 code and tests | "A refused move changes nothing." |
| 15 to 22 | `SearchIndex` and the inverted index | "Words to ids. Intersect for AND." |
| 22 to 30 | Dirty set, drain, allow-list, the freshness promise | "Index only from committed state. Read-your-writes is a switch." |
| 30 to 38 | Trackers per project, read-time filter, move | "The tracker cannot forget an id, so I filter on read, then cut to k." |
| 38 to 45 | End-to-end story, then the gaps | "Production: durable outbox, sharded index, write outside the lock." |

## 6. Senior vs Staff Rubric

| Area | Senior | Staff |
|---|---|---|
| Permissions | A role check in each method | One seam, deny by default, same order everywhere, hidden equals not found, bulk reuses the single path |
| Search | Filters after the query | Allow-list inside the query, states and tests the freshness promise |
| Consistency | Index inside the transition | Index only from committed state, no lost marks, retry after failure, hook plus own marks, migration covered |
| Trending | One global counter | Per tenant tracker, read-time filter before the cut, labels derived from live issues |
| Testing | Happy paths | Differential tests with an independent oracle, mutants for every bypass, forced interleaving, exact end-to-end outputs |

## 7. Key Takeaways

- Every path asks the same question in the same order: see it, may do it, then the workflow's rules.
- Deny by default, and make "hidden" look exactly like "missing".
- Copies (index, counters) are rebuilt from committed state; a refused change must not reach them.
- Remove the dirty mark before the write, not after, or a concurrent change is lost.
- Pass the allow-list into the query. Do not filter afterwards.
- Say what you promise about freshness, then test exactly that.
- A counter that cannot forget needs a read-time filter, applied before the cut to k.
- Reuse finished parts (42, 45) and spend the time on the seams between them.

**Self-grade: STRONG.** Full spine (interface and error contract, entities and invariants, ASCII diagram, costs), three rounds each with a prompt, a strong answer, a code delta and a proving test, 63 tests, 46 mutants plus a control checked with the exact edits in `commands.md`. Remaining gap: everything is in memory under one lock (index writes happen under it), so the durable outbox, sharded index, permission caching and write-outside-the-lock are designed (F1 to F3) but not coded, the scheme has no groups or issue-level security, and the engine never forgets deleted issues.

[← Topic 47: Capstone, Ticketing Platform](../47-capstone-ticketing-platform/) · [Index](../README.md) · [Back to the Index (this is the last topic) →](../README.md)
