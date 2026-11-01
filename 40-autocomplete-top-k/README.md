# Topic 40: Autocomplete Top-K

"Design autocomplete." It looks like a trie lookup. The interviewer is waiting for the real problem: the answer must be the K most popular words, in under a millisecond, while counts keep changing, and a count that goes DOWN is the case that breaks the obvious design.

A staff answer keeps the best K words at every trie node, updates them along one path, repairs them exactly when a count falls, ranks ties by a stated rule, tolerates one typo with a bounded search, and shows how the index splits across servers.

## Table of Contents

1. [The Problem](#1-the-problem)
2. [How It Solves It](#2-how-it-solves-it)
3. [The Gaps](#3-the-gaps)
4. [Official References](#4-official-references)
5. [45-Minute Interview Timeline](#5-45-minute-interview-timeline)
6. [Senior vs Staff Rubric](#6-senior-vs-staff-rubric)
7. [Key Takeaways](#7-key-takeaways)

## 1. The Problem

### Worked example first

A *trie* is a tree where each step down is one letter, so all words with the same start share the same path. A *prefix* is the letters typed so far. Every node also keeps a *top list*: the best K words anywhere in its *subtree* (the node and everything below it; here K = 2). Words: `apple` 5, `apply` 3, `app` 3, `ape` 9, `bat` 1. Test: `TrieTest.workedExample`.

```
 root  top: ape:9, apple:5
  |-- a -- p   top(ap): ape:9, apple:5
  |        |-- p  (word "app":3)  top(app): apple:5, app:3      <- apply:3 loses the tie to app ("app" sorts before "apply")
  |        |    |-- l -- e (apple:5)   -- y (apply:3)
  |        `-- e  (ape:9)
  `-- b -- a -- t (bat:1)
```

| Step | Request | What happens | Result |
|---|---|---|---|
| 1 | `suggest("ap", 2)` | Walk a, p (2 steps), copy the list on that node | ape:9, apple:5 |
| 2 | `suggest("", 2)` | The root node is the empty prefix, copy its list | ape:9, apple:5 |
| 3 | `suggest("zzz", 5)` | The walk falls off the trie | empty |
| 4 | `suggest("ap", 50)` | 50 is more than K, so scan the subtree and sort | all 4 words, ape first |

Cost of a read: O(L + K), where L is the prefix length. It does not depend on how many words exist.

### v1 requirements (assumed answers)

| Question | Assumed answer |
|---|---|
| Alphabet? | Any `char` (a `HashMap` of children). Case-sensitive: "Apple" and "apple" differ. Unicode case folding and normalisation are out of scope: lower-case before calling. Characters outside the BMP are two `char`s, so a prefix can end between them. |
| Ranking? | Higher count first. Equal counts: smaller word first (`String.compareTo`). One rule, in `Suggestion.ORDER`, used by the trie and by the test oracle. |
| Counts? | `long`, bumped by a positive or negative delta. A count that reaches 0 removes the word. Below 0, or overflow, is an error. |
| Bad input? | `null` or empty word, `null` prefix, negative k, delta 0: `IllegalArgumentException`, nothing changes. Empty prefix is allowed. |
| Out of scope | Personalisation, spell-correct by meaning, scoring by recency, persistence, REST. |

## 2. How It Solves It

### Entities and invariants

| Entity | Meaning |
|---|---|
| `Autocomplete` | The interface and its error contract. |
| `Suggestion(word, count)` | One answer, plus the ranking order. |
| `TrieAutocomplete` | The trie with a top list per node. Not thread-safe. |
| `ConcurrentAutocomplete` | A read-write lock around any `Autocomplete`. |
| `ShardedAutocomplete` | The F3 router over several indexes. |

- I1: every node's top list is exactly the best `min(K, words below)` words of its subtree, in rank order.
- I2: no node exists that has no word below it (deleted words cut their empty branch).
- I3: `size()` equals the number of distinct words with count above 0.

### The interface

```java
public interface Autocomplete {
    void add(String word, long delta);               // delta > 0 raises, delta < 0 lowers, delta 0 throws; a count that reaches 0 removes the word
    boolean remove(String word);
    long count(String word);
    List<Suggestion> suggest(String prefix, int k);
    List<Suggestion> suggestFuzzy(String prefix, int k); // prefix up to 256 chars
    int size();
}
```

### Class diagram

```
 Autocomplete <|-- TrieAutocomplete --> root: Node{ children: Map<char,Node>, self: Suggestion, top: List<Suggestion> }
              <|-- ConcurrentAutocomplete --> delegate, ReentrantReadWriteLock
              <|-- ShardedAutocomplete    --> shards[ ], routeLen
```

### Update: raise a count (F1) and the trap when it falls

Timeline first. K = 2, `apply` goes from 3 to 13 by adding 10 (test `incrementMovesWordUp`):

| Step | Node | Top list before | Action | After |
|---|---|---|---|---|
| 1 | root | ape:9, apple:5 | remove old apply, insert apply:13 at the front, cut to 2 | apply:13, ape:9 |
| 2 | a, ap | same | same | apply:13, ape:9 |
| 3 | app | apple:5, app:3 | same | apply:13, apple:5 |

Why this is enough: raising a count can only help that one word, so at each node on its path remove its old entry, insert the new one in rank order, and drop the last entry if the list is longer than K. That is L nodes times K work: O(L x K).

Lowering is different. `app` and `apply` were never in the list of node `ap` (ape and apple beat them), so the list has no idea they exist. Drop `ape` to 0 and `apple` to 1: the right answer for `ap` is now `app:3, apply:3`, but the list only holds `apple:1` and nothing to refill it. This is the cached top-K trap. Test: `decrementPullsInAWordThatWasNeverCached` (first asserts the two words were not cached).

The fix here (timeline below): repair, bottom-up along the path, every node whose list held the changed word. The new list is the node's own word plus the union of the children's lists, sorted and cut to K. This is exact because the best K of a subtree are always among the best K of each child, and each child's list is already correct (we fix children first). Limit, stated: a `suggest` with k above K (the cache size) scans the subtree instead (`kLargerThanMatchesAndThanCache`).

| Step (decrease `ape` to 0, K = 2) | Node | Held `ape`? | Action |
|---|---|---|---|
| 1 | `ape` leaf | yes, then empty | cut the leaf from its parent (I2) |
| 2 | `ap` | yes | rebuild from own word (none) plus the children's lists (`app`: apple:5, app:3; `apl`: none): apple:5, app:3 |
| 3 | `a`, root | yes | rebuild from children: apple:5, app:3 (bat:1 does not fit in K = 2) |

Cost of the repair: O(L x branching x K log K), paid only on lowers and deletes.

Alternative (not coded): keep a sorted structure of ALL words per node. Exact and no rebuild, but memory is the sum of subtree sizes, so about L times the words.

### The concurrency model

Shared state: the whole trie. One write touches every node on a path and may rebuild lists, so a reader in the middle sees a torn path (I1 broken). I chose `ConcurrentAutocomplete`, a `ReentrantReadWriteLock`: many `suggest` calls run together, a write runs alone. Why not copy-on-write (an immutable trie published through a `volatile`)? A write would copy L nodes with their maps and lists, and if every search bumps a count writes are frequent. (In production, as the delta below says, counts are batched, and then a snapshot rebuilt per batch is the better fit; this core layer is the live, per-update one.) The cost of the lock: readers wait while a write runs. Single-writer plus snapshot is the other option. The primitives are explained in [Java concurrency: read-write locks](../../internal-pocs/Java-poc/concurrency/18-readwrite-and-stampedlock/) and [copy-on-write](../../internal-pocs/Java-poc/concurrency/29-copy-on-write-and-skiplist/).

Tests (no sleeps): `readerWaitsForTheWriterAndSeesTheFinishedUpdate` freezes a writer inside its hook, starts a reader, and checks the lock reports that reader queued (`hasQueuedThread`), then sees the finished update. `readersDoNotBlockEachOther` makes two readers wait for each other inside the read lock. `everyReadMethodWaitsForTheWriter` and `removeHoldsTheWriteLock` repeat the probe for the other readers and for `remove`. `concurrentWritersLoseNothing`: 8 writers behind a start gate, exact totals, and any exception in a thread fails the test.

### Typo tolerance (F2): the algorithm

*Edit distance* is the fewest single-letter changes (insert, delete, substitute) to turn one text into another. *Levenshtein* counts those three. *Damerau-Levenshtein* also counts swapping two neighbours as one change. We use **Levenshtein**: a swap such as `fomr` for `form` costs 2.

Definition used by the code and by the test oracle: a word *fuzzy-matches* query q if it starts with q (exact), or if some non-empty prefix of the word is within distance 1 of q. Ranking: all exact matches first (by rank order), then fuzzy-only ones (by rank order). So `cart:1000` ranks below `cat:1` for query `cat`.

Timeline, query `hxl`, words `hello`, `help`, `world`. The table shows the full row of numbers per trie level: row[j] = distance from the letters walked so far to the first j letters of the query. The code keeps only the 3 cells near the diagonal (explained after the table).

| Step | Letters walked | Row (j = 0..3) | Min | Decision |
|---|---|---|---|---|
| 0 | (root) | 0 1 2 3 | 0 | start |
| 1 | `h` | 1 0 1 2 | 0 | continue |
| 2 | `he` | 2 1 1 2 | 1 | continue |
| 3 | `hel` | 3 2 2 1 | 1 | last cell is 1: `hel` is one typo from `hxl`, so take the cached top list of this node and stop going down |
| 4 | `w` | 1 1 2 3 | 1 | continue, then `wo` has min 2: cut |

Two rules keep the work small. A branch whose whole row is above 1 is cut, because letters only add cost. A node whose last cell is at most 1 contributes its cached list, and its subtree is not walked. Also, a cell (d letters walked, j query letters) is at least |d - j|, so only the 3 cells j = d-1, d, d+1 can ever be 1 or less: the code computes just those 3, so a step costs the same for a 5-letter and a 200-letter query. The bound is empirical, not a proof: on a dense 20,000-word trie the rows computed stay below half the node count, a 10 times longer query (20 to 200 letters) costs less than 12 times the rows, and subtree scans (only when k is above the cache) look at fewer nodes than the trie has. Test: `fuzzyWorkIsBounded` (a mutant that never cuts computes 121,906 rows for m = 5, nearly the whole trie of 122,296 nodes).

Surprises, stated: a query of 1 or 2 letters is within distance 1 of many short prefixes, so it matches nearly everything (`x` is one substitution away from the one-letter prefix of any word, so it matches every word). And because only a prefix has to be close, `fomr` finds `form` through the prefix `for`. A swap of the whole word (`bacd` for `abcd`) is not found.

### F3: the sharding seam

One server cannot hold every word forever. `ShardedAutocomplete` routes by the first `routeLen` letters (hash, modulo shard count): a prefix of that length or more goes to exactly one shard; a shorter one asks all shards and merges their best k, which is exact because the shards hold disjoint words. Fuzzy always asks all shards. Test: `shardedMatchesBruteForceAfterEveryUpdate` (60 seeds, plain and fuzzy, after every update), `longPrefixGoesToOneShard`.

Example: 4 shards, routeLen 2, words `apple` and `apply` (same first 2 letters `ap`, so same shard) and `bat` (shard chosen by `ba`). `suggest("app")` asks only the shard of `ap`. `suggest("a")` is shorter than 2 letters, so it asks all 4 shards and keeps the best k of their answers.

| Prefix length | Shards asked | Why |
|---|---|---|
| at least routeLen | 1 | all words with it live together |
| below routeLen (including empty) | all | the words could be anywhere |

Delta for production: hash the key with a consistent hash ([Topic 29](../29-consistent-hashing/)), because plain modulo moves most keys when a shard is added. Popular prefixes (`a`, `th`) make hot shards; the fix is a short-prefix cache in front, or splitting a hot key by a second letter.

### Code and tests

| File | Role |
|---|---|
| [`Autocomplete`](src/main/java/com/lld/autocomplete/Autocomplete.java), [`Suggestion`](src/main/java/com/lld/autocomplete/Suggestion.java) | Interface, error contract, ranking order. |
| [`TrieAutocomplete`](src/main/java/com/lld/autocomplete/TrieAutocomplete.java) | Trie, top lists, raise, repair, fuzzy walk. |
| [`ConcurrentAutocomplete`](src/main/java/com/lld/autocomplete/ConcurrentAutocomplete.java), [`ShardedAutocomplete`](src/main/java/com/lld/autocomplete/ShardedAutocomplete.java) | Read-write lock wrapper; shard router. |
| `TrieTest` (14), `FuzzyTest` (11) | Hand-written cases; typo kinds, ranking, work bound, differential. |
| `DifferentialTest` (2), `ShardAndSizeTest` (4), `ConcurrencyTest` (5) | Brute force after every update; router and memory; lock behaviour. |
| `BenchmarkTest` (1, off by default) | Build time and latency, `-Dautocomplete.bench=true`. |

## 3. The Gaps

| Rung | Delta from the one before |
|---|---|
| F1 frequency updates | `add` with a delta: raise along the path, repair on lower. No rebuild of the whole trie. |
| F2 typo tolerance | One row of numbers per level, cut at distance 2, cached lists at matching nodes. |
| F3 sharding seam | A router that picks shards by the first letters. |

### The differential tests

`DifferentialTest` runs 300 seeds of 60 steps on a 3-letter alphabet (so words share prefixes and collide a lot) with K = 2 or 3 so the trap fires often. After EVERY step, for the empty prefix, a random prefix, a stored word, an absent prefix and k = 1, 2, 3, 5, 100 (more than the matches), the trie must equal `Reference`, a map plus a full scan and sort. It asserts the run had exactly 18,000 steps, over 3,000 lowers or deletes, and over 2,000 list repairs, so a weak generator fails. A second test checks that the cached list at every node on recent words equals the brute-force best K. `FuzzyTest` does the same for fuzzy answers against a full Levenshtein table and asserts over 5,000 cases where fuzzy matches changed the answer. An *oracle* here is a simple, obviously-correct model that the fast code is checked against. It shares only `Suggestion.ORDER` and the fuzzy-match definition with the engine, so a bug in the comparator or in that definition is guarded only by the hand-pinned tests (`tiesAreBrokenByWord`, the typo-kind tests), not by the differentials.

### Measurements

Setup: Apple M3 Max, JDK 21.0.8, one JVM (`-Xmx4g`), seeded synthetic words (2 to 5 syllables from a list of 30, not a real dictionary, so prefix sharing may differ from English). K = 10. The *JIT* (the JVM's compiler that speeds up hot code) is warmed with 3 passes first. Build: median of 3 runs. Suggest: a whole loop of 20,000 prefix queries (length 0 to 4) is timed and divided, 5 runs, median and min to max. Fuzzy: 2,000 queries of "a leading typo, x plus the first 4 letters of a stored word", the same way. Heap: bytes in use after the build minus a baseline taken after the word list exists and before the build. Command: `mvn -o -pl 40-autocomplete-top-k test -Dautocomplete.bench=true -Dtest=BenchmarkTest -DargLine="-Xmx4g"`.

| N words | Trie nodes | Build median (min-max) | Suggest ns/call median (min-max) | Fuzzy us/call median | Heap above baseline per word |
|---|---|---|---|---|---|
| 10,000 | 43,233 | 14 ms (11-24) | 43 (40-56) | 2.5 | 651 B |
| 100,000 | 338,559 | 137 ms (135-143) | 38 (35-70) | 3.2 | 591 B |
| 1,000,000 | 2,774,463 | 2,160 ms (2,076-2,261) | 50 (45-53) | 3.5 | 484 B |

Reading it: a plain suggest costs about 40 to 50 ns whether there are 10 thousand or 1 million words (the 1M run is a little slower, probably cache misses on a much bigger trie), so the cost follows the prefix length and K, not N. Build is roughly linear (about 2 us per word). Fuzzy stays at a few microseconds because the walk is cut at distance 2. The numbers are one machine and synthetic words, so treat them as this-toy-on-this-machine; heap per word is a rough estimate from `totalMemory - freeMemory` after two `System.gc()` calls.

Memory shape (`ShardAndSizeTest.nodesPerWord`, 20,000 words of 9.7 letters on average): 4.10 nodes per word, and 5.76 cached list slots per word. Shared prefixes make a word cost far fewer nodes than letters, but every node carries a map and a list, which is why a node costs on the order of 100 bytes in Java. A real engine packs this (arrays, a radix tree, off-heap).

### Misconceptions and common mistakes

| Mistake | Why wrong | Test that catches it |
|---|---|---|
| Update top lists only on increments | A fall or delete leaves a stale list that misses words which were never cached | `decrementPullsInAWordThatWasNeverCached`, the differentials |
| Leave the old entry when a count rises | The word appears twice in a list | differentials |
| Not cutting empty branches after delete | Memory leaks and `size`-like checks lie | `removingEverythingEmptiesTheTrie` |
| Ties left to hash order | Two runs give different lists; tests flake | `tiesAreBrokenByWord` |
| Fuzzy search that tries all edits of the query | Blows up with query length | `fuzzyWorkIsBounded` |
| Fuzzy results mixed with exact by count | A popular typo match beats the thing typed | `exactPrefixMatchesRankAboveFuzzyOnes` |
| Recursion per letter | A very long word overflows the stack | `veryLongWordDoesNotOverflowTheStack` (100,000 letters) |

### Trade-offs and when not to use

Top lists make reads O(L + K) but cost K entries per node and make every write walk its path. Rebuild on lower is paid by the writer. If counts are rarely lowered, that is almost free. If you only need a few thousand words, sorting a list per query is simpler and fast enough.

### Production and distributed delta

- **Counts come from logs.** Real systems do not bump on every keystroke; they batch query logs and rebuild or merge periodically (Topic 41 has the ranking side). Our incremental update fits a "hot" layer on top of a rebuilt base.
- **Ranking is more than count:** recency, personalisation, language. These change only the comparator; the structure stays.
- **Serving.** Put the top lists for short prefixes in a cache; they take most traffic. See [Redis-poc](../../pocs/Redis-poc/) for sorted-set based alternatives.

## 4. Official References

- Princeton, Sedgewick and Wayne, [Algorithms 4th edition, section 5.2 Tries](https://algs4.cs.princeton.edu/52trie/) (200). Book: Sedgewick, *Algorithms*, 4th edition, section 5.2; Knuth, *The Art of Computer Programming* volume 3, section 6.3 (digital searching), not opened for this topic.
- Wikipedia: [Trie](https://en.wikipedia.org/wiki/Trie), [Levenshtein distance](https://en.wikipedia.org/wiki/Levenshtein_distance), [Damerau-Levenshtein distance](https://en.wikipedia.org/wiki/Damerau%E2%80%93Levenshtein_distance), [Autocomplete](https://en.wikipedia.org/wiki/Autocomplete) (all 200).
- Oracle, [ReentrantReadWriteLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantReadWriteLock.html) (200).

## 5. 45-Minute Interview Timeline

| Time | What you do |
|---|---|
| 0-8 | Clarify (ranking, ties, alphabet, updates, typos). Draw the small trie and a read. |
| 8-20 | Interface and error contract. Top list per node, read cost O(L + K), raise along the path. |
| 20-30 | The trap: a fall or delete. Rebuild from children, say why it is exact, name the limit k above K. |
| 30-38 | Typo tolerance: distance rows, the cut at 2, exact before fuzzy, Levenshtein vs Damerau. |
| 38-43 | Concurrency (read-write lock vs snapshot) and the sharding seam. |
| 43-45 | Testing: differential after every update, mutation checks, numbers. |

## 6. Senior vs Staff Rubric

| Signal | Senior | Staff |
|---|---|---|
| Structure | Trie, DFS the subtree on each query | Top list per node, O(L + K) read, cost of writes stated |
| Updates | Only increments | The decrement/delete trap named, repaired exactly, tested and mutation-checked |
| Ties | Unspecified | One rule on both sides of the test |
| Typos | Generate all edits of the query | Distance rows with a cut, bounded work shown by a count, exact above fuzzy, Damerau out of scope stated |
| Concurrency | "Make it synchronized" | Chosen model with the reason, deterministic tests |
| Scale | "Use a database" | Prefix routing, short-prefix fan-out, consistent hashing, hot prefixes |

## 7. Key Takeaways

1. Keep the best K at every node and a read is a walk plus a copy.
2. A raise is one path of O(K) edits. A fall or delete needs a repair, because a word never cached may now belong: rebuild from the children.
3. Fix one tie rule and use it everywhere, including the test oracle.
4. Typo tolerance is one distance row per level with a cut at 2. Exact matches rank above fuzzy ones.
5. Test against brute force after every update, and prove the tests catch bugs by mutation.

**Self-grade: STRONG.** Full spine, v1, F1, F2 and F3 coded with 36 tests (35 run by default), differentials after every update, 15 killed mutants plus 8 probed ones (`commands.md`). Gaps: the words are synthetic, not a real list; memory per word is a rough estimate; the fuzzy bound is empirical, not a proof; short fuzzy queries match nearly everything; no Damerau, Unicode folding or persistence; the shard router is in-process; Knuth volume 3 was not opened.

[← Topic 39: Undo/Redo Editor](../39-undo-redo-editor/) · [Index](../README.md) · [Topic 41: Leaderboard →](../41-leaderboard/)
