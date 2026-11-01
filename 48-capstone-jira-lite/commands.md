# Topic 48: Capstone, Jira-lite, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer. The module reuses Topics 42 and 45, so they must be installed in the local Maven repository once (`mvn -o -q -pl 42-workflow-engine,45-content-popularity -am install -DskipTests`).

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 48-capstone-jira-lite test                                  # 36 tests; no output means green
mvn -o -q -pl 48-capstone-jira-lite test -Dtest=RoundTwoTest              # search, freshness, differential test
mvn -o -q -pl 48-capstone-jira-lite test -Dtest=EndToEndTest              # the whole story in virtual time
```

Measured (JDK 21.0.8, Apple silicon laptop, 3 runs): `BUILD SUCCESS`, 36 tests, 0.97 s, 1.16 s and 0.97 s total Maven time. Per class: RoundOneTest 12, RoundTwoTest 12, RoundThreeTest 11, EndToEndTest 1.

## 2. Gates

```bash
mvn -o -pl 48-capstone-jira-lite test-compile | rg WARNING              # prints nothing
python3 scripts/check_style.py 48-capstone-jira-lite                    # 0 problem(s)
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now\(' 48-capstone-jira-lite/src   # prints nothing
```

## 3. Mutation self-check

Each row is one change (the exact old text becomes the exact new text, whitespace squeezed here) to one main file, compiled and run twice with a 1 s pause after each rewrite. "Killed" means at least one test failed (the number is how many). Every mutant compiled, and after the whole run the sources were compared with a backup and were identical. M = original set, S = survivors found in review, N = new traps (lost mark, empty query, migration, lag-mode delete, check order, contract details). C1 is a control that changes nothing observable, so it must survive.

| # | What it breaks | File | Edit | Run 1 | Run 2 |
|---|---|---|---|---|---|
| M1 | transition skips TRANSITION right | `JiraService.java` | `require(user, m.project, Action.TRANSITION); engine.transition` becomes `engine.transition` | killed (3) | killed (3) |
| M2 | comment skips COMMENT right | `JiraService.java` | `require(user, m.project, Action.COMMENT);` becomes (deleted) | killed (1) | killed (1) |
| M3 | visible() ignores BROWSE | `JiraService.java` | `\|\| !permissions.allows(m.project, user, Action.BROWSE)` becomes (deleted) | killed (5) | killed (5) |
| M4 | search allow-list = every project | `JiraService.java` | `if (permissions.allows(p, user, Action.BROWSE)) {` becomes `if (true) {` | killed (5) | killed (5) |
| M5 | trendingIssues skips BROWSE | `JiraService.java` | `require(user, project, Action.BROWSE); tracker(project); return liveRanked` becomes `tracker(project); return liveRanked` | killed (2) | killed (2) |
| M6 | trendingLabels skips BROWSE | `JiraService.java` | `require(user, project, Action.BROWSE); tracker(project); Map<String, Long> sums` becomes `tracker(project); Map<String, Long> sums` | killed (1) | killed (1) |
| M7 | bulk bypasses single-issue checks | `JiraService.java` | `transition(user, id, transitionName, WorkflowEngine.ANY_VERSION);` becomes `engine.transition(id, transitionName, user, WorkflowEngine.ANY_VERSION); dirty.add(id);` | killed (3) | killed (3) |
| M8 | failed index write forgets the mark | `JiraService.java` | `} catch (RuntimeException e) { dirty.add(id);` becomes `} catch (RuntimeException e) {` | killed (1) | killed (1) |
| M9 | trending keeps deleted/moved issues | `JiraService.java` | `!m.gone &&` becomes (deleted) | killed (4) | killed (4) |
| M10 | move leaves old issue alive | `JiraService.java` | `m.gone = true; dirty.add(issueId); return details(copy);` becomes `dirty.add(issueId); return details(copy);` | killed (4) | killed (4) |
| M11 | move skips CREATE in target | `JiraService.java` | `require(user, targetProject, Action.CREATE);` becomes (deleted) | killed (1) | killed (1) |
| M12 | delete skips ADMINISTER | `JiraService.java` | `require(user, m.project, Action.ADMINISTER); m.gone = true;` becomes `m.gone = true;` | killed (1) | killed (1) |
| M13 | index remove leaves old words | `InvertedIndex.java` | `ids.remove(id);` becomes (deleted) | killed (4) | killed (4) |
| M14 | label filter case-sensitive | `JiraService.java` | `label == null ? null : label.toLowerCase(Locale.ROOT)` becomes `label` | killed (1) | killed (1) |
| M15 | search ignores read-your-writes | `JiraService.java` | `if (readYourWrites) {` becomes `if (false) {` | killed (12) | killed (12) |
| M16 | scheme allows by default | `RoleBasedPermissionScheme.java` | `return false; } }` becomes `return true; } }` | killed (22) | killed (22) |
| M17 | text words OR instead of AND | `InvertedIndex.java` | `candidates.retainAll(ids);` becomes `candidates.addAll(ids);` | killed (3) | killed (3) |
| M18 | index ignores project allow-list | `InvertedIndex.java` | `q.projects().contains(d.project())` becomes `true` | killed (7) | killed (7) |
| M19 | transition not counted for trending | `JiraService.java` | `trackers.get(m.project).record(issueId);` becomes (deleted) | killed (2) | killed (2) |
| M20 | view not counted for trending | `JiraService.java` | `trackers.get(m.project).record(m.id); return details(m);` becomes `return details(m);` | killed (10) | killed (10) |
| S1 | transitions trending asks tracker for only k | `JiraService.java` | `tracker(project).topK(Integer.MAX_VALUE)` becomes `tracker(project).topK(k)` | killed (1) | killed (1) |
| S2 | label trending cuts to k before summing | `JiraService.java` | `liveRanked(project, Integer.MAX_VALUE)` becomes `liveRanked(project, k)` | killed (1) | killed (1) |
| S3 | move skips ADMINISTER in source | `JiraService.java` | `require(user, m.project, Action.ADMINISTER); require(user, targetProject, Action.BROWSE);` becomes `require(user, targetProject, Action.BROWSE);` | killed (1) | killed (1) |
| S4 | transition does not mark dirty | `JiraService.java` | `engine.transition(issueId, transitionName, user, expectedVersion); dirty.add(issueId);` becomes `engine.transition(issueId, transitionName, user, expectedVersion);` | killed (1) | killed (1) |
| S5 | comment does not mark dirty | `JiraService.java` | `m.comments.add(text); dirty.add(issueId);` becomes `m.comments.add(text);` | killed (1) | killed (1) |
| S6 | assign does not mark dirty | `JiraService.java` | `m.assignee = assignee == null ? "" : assignee; dirty.add(issueId);` becomes `m.assignee = assignee == null ? "" : assignee;` | killed (1) | killed (1) |
| S7 | delete does not mark dirty | `JiraService.java` | `m.gone = true; dirty.add(issueId); }` becomes `m.gone = true; }` | killed (1) | killed (1) |
| S8 | assign writes before the permission check | `JiraService.java` | `Meta m = visible(user, issueId); require(user, m.project, Action.TRANSITION); m.assignee = assignee == null ? "" : assignee;` becomes `Meta m = visible(user, issueId); m.assignee = assignee == null ? "" : assignee; require(user, m.project, Action.TRANSITION);` | killed (1) | killed (1) |
| S9 | assign skips visible() | `JiraService.java` | `Meta m = visible(user, issueId); require(user, m.project, Action.TRANSITION); m.assignee` becomes `Meta m = metas.get(issueId); require(user, m.project, Action.TRANSITION); m.assignee` | killed (1) | killed (1) |
| S10 | delete skips visible() | `JiraService.java` | `Meta m = visible(user, issueId); require(user, m.project, Action.ADMINISTER); m.gone = true;` becomes `Meta m = metas.get(issueId); require(user, m.project, Action.ADMINISTER); m.gone = true;` | killed (1) | killed (1) |
| S11 | move skips visible() | `JiraService.java` | `Meta m = visible(user, issueId); require(user, m.project, Action.ADMINISTER); require(user, targetProject, Action.BROWSE);` becomes `Meta m = metas.get(issueId); require(user, m.project, Action.ADMINISTER); require(user, targetProject, Action.BROWSE);` | killed (1) | killed (1) |
| S12 | tokenizer drops digits | `InvertedIndex.java` | `[^\\p{L}\\p{Nd}]+` becomes `[^a-z]+` | killed (3) | killed (3) |
| S13 | tokenizer keeps empty words | `InvertedIndex.java` | `if (!w.isEmpty()) {` becomes `if (true) {` | killed (1) | killed (1) |
| S14 | labels not sorted | `JiraService.java` | `List.copyOf(new TreeSet<>(m.labels))` becomes `List.copyOf(m.labels)` | killed (1) | killed (1) |
| N1 | lost mark: remove after the write | `JiraService.java` | `if (!dirty.remove(id)) {` becomes `if (!dirty.contains(id)) {`; `m.comments))); } } catch (RuntimeException e) {` becomes `m.comments))); } dirty.remove(id); } catch (RuntimeException e) {` | killed (1) | killed (1) |
| N2 | empty-word query not short-circuited | `InvertedIndex.java` | `if (terms.isEmpty()) {` becomes `if (false) {` | killed (1) | killed (1) |
| N3 | migrate does not mark dirty | `JiraService.java` | `dirty.add(m.id);` becomes (deleted) | killed (1) | killed (1) |
| N4 | search keeps gone ids (lag mode) | `JiraService.java` | `if (m != null && !m.gone) { live.add(id);` becomes `if (m != null) { live.add(id);` | killed (2) | killed (2) |
| N5 | create skips BROWSE | `JiraService.java` | `require(user, project, Action.BROWSE); require(user, project, Action.CREATE);` becomes `require(user, project, Action.CREATE);` | killed (1) | killed (1) |
| N6 | move target skips BROWSE | `JiraService.java` | `require(user, targetProject, Action.BROWSE); require(user, targetProject, Action.CREATE);` becomes `require(user, targetProject, Action.CREATE);` | killed (1) | killed (1) |
| N7 | move audit actor is the reporter | `JiraService.java` | `engine.createIssue(project, id, fields, actor);` becomes `engine.createIssue(project, id, fields, reporter);` | killed (1) | killed (1) |
| N8 | bulk does not catch unexpected errors | `JiraService.java` | `} catch (RuntimeException e) { out.put(id, Outcome.ERROR); }` becomes `}` | killed (1) | killed (1) |
| N9 | unknown project not detected | `JiraService.java` | `if (t == null) { throw new JiraException.ProjectNotFound(project); }` becomes (deleted) | killed (1) | killed (1) |
| N10 | null assignee stored as null | `JiraService.java` | `m.assignee = assignee == null ? "" : assignee;` becomes `m.assignee = assignee;` | killed (1) | killed (1) |
| N11 | outage message leaks key and cause | `JiraService.java` | `"search is temporarily unavailable", e` becomes `"could not index " + id + ": " + e, e` | killed (1) | killed (1) |
| C1 | CONTROL (keeps empty word sets, no visible change) | `InvertedIndex.java` | `if (ids.isEmpty()) {` becomes `if (false) {` | survived | survived |

The harness lived in `/tmp/g48/mut.py` and is not part of the topic. A mutant whose old text is not found exactly once is reported by the harness instead of being counted as killed. M11 and N1 were first run before a test and a pattern fix and re-run after (M11 had survived once, which is why `moveNeedsCreateInTheTarget` exists).

A second, independent run by the lead (29 mutants written from the review list, plus a control) killed everything except two. The first was "bump the key counter before the engine accepts the create": no test made the engine refuse a create. `HardeningTest.engineRefusedCreateBurnsNoKey` now does (a null actor), and the mutant is killed 2/2. The second was "trending skips the same-project check": it is equivalent here, because a move deletes the old id and makes a new one, so a live id never changes project.

## 4. What the output shows

The permission order is visible in `RoundOneTest.permissionIsCheckedBeforeTheWorkflow`: a viewer asking for an illegal move gets `PermissionDenied`, and the engine's version stays 0. The freshness promise is visible in `RoundTwoTest.lagUntilRefreshWhenNotPromised`: 4 pending writes, an empty search, then `refresh()` and an exact answer. The lost-mark trap is `HardeningTest.changeDuringAnIndexWriteIsNotLost`: a probe makes the engine change the issue during the index write, the first `refresh()` leaves 1 pending, the second leaves 0 and search shows IN_PROGRESS.
