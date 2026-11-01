# Topic 40: Autocomplete Top-K, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests (core module only, no Spring)

```bash
mvn -q -pl 40-autocomplete-top-k test                                          # all 35 tests, about 2 seconds; no output means all passed
mvn -q -pl 40-autocomplete-top-k test -Dtest=DifferentialTest                  # trie vs brute force after every update
mvn -q -pl 40-autocomplete-top-k test -Dtest=FuzzyTest                         # typo tolerance, differential and work bound
mvn -q -pl 40-autocomplete-top-k test -Dtest='TrieTest#decrementPullsInAWordThatWasNeverCached'   # the cached top-K trap
mvn -q -pl 40-autocomplete-top-k test -Dtest=ShardAndSizeTest#nodesPerWord     # prints nodes and cache slots per word
```

Real output of the memory test: `memory: 20000 words, 9.7 letters/word, 4.10 nodes/word, 5.76 cached slots/word`.

## 2. The benchmark (off by default)

```bash
mvn -o -pl 40-autocomplete-top-k test -Dautocomplete.bench=true -Dtest=BenchmarkTest -DargLine="-Xmx4g"
```

Output on Apple M3 Max, JDK 21.0.8 (build is the median of 3 runs; suggest and fuzzy time a whole loop of queries and divide, because one `nanoTime` tick is about 40 ns):

```
N=10,000 nodes=43,233 build median 14 ms (min 11, max 24), suggest ns/call median 43 (min 40, max 56), fuzzy us/call median 2.5, heap above baseline 651 bytes/word
N=100,000 nodes=338,559 build median 137 ms (min 135, max 143), suggest ns/call median 38 (min 35, max 70), fuzzy us/call median 3.2, heap above baseline 591 bytes/word
N=1,000,000 nodes=2,774,463 build median 2160 ms (min 2076, max 2261), suggest ns/call median 50 (min 45, max 53), fuzzy us/call median 3.5, heap above baseline 484 bytes/word
```

## 3. Mutation self-check

For each key invariant, one line of the code was broken, the whole suite run (with JDK 21), and the line restored. Re-run on the final code. Remember `JAVA_HOME`: under the default Java 17 every run fails to compile and every mutant looks killed.

| Mutation (one line) | Result | First test that fails |
|---|---|---|
| Trap: skip the list rebuild after a decrease or delete | KILLED | `DifferentialTest.cachedListsAreExactlyTheBestKEverywhere`, `ConcurrencyTest.removeHoldsTheWriteLock` |
| Fuzzy accepts distance 2 | KILLED | `FuzzyTest.distanceTwoIsRejected`, fuzzy differential |
| Fuzzy never cuts a branch | KILLED | `FuzzyTest.fuzzyWorkIsBounded` (121,906 rows, nearly the whole 122,296-node trie) |
| `remove` of an interior non-word prefix: `n.self == null` check dropped | KILLED | `TrieTest.prefixThatIsNotAWordIsNotThere` |
| `count` of an interior non-word prefix: same check dropped | KILLED | `TrieTest.prefixThatIsNotAWordIsNotThere` |
| Repair rebuilds every node on the path, not only those that held the word | KILLED | `TrieTest.repairTouchesOnlyListsThatHeldTheWord` |
| `suggest` never uses the cached list (always scans) | KILLED | `FuzzyTest.cachedPathNeverScans` |
| Fuzzy never uses the cached list (always scans) | KILLED | `FuzzyTest.cachedPathNeverScans` |
| Router: prefix of exactly routeLen asks all shards (`>` instead of `>=`) | KILLED | `ShardAndSizeTest.longPrefixGoesToOneShard` |
| Fuzzy prefix limit off by one (`>=` instead of `>`) | KILLED | `FuzzyTest.prefixLengthBoundaryIsPinned` |
| `suggestFuzzy` without the read lock | KILLED | `ConcurrencyTest.everyReadMethodWaitsForTheWriter` |
| `count` without the read lock | KILLED | `ConcurrencyTest.everyReadMethodWaitsForTheWriter` |
| `size` without the read lock | KILLED | `ConcurrencyTest.everyReadMethodWaitsForTheWriter` |
| `remove` without the write lock | KILLED | `ConcurrencyTest.removeHoldsTheWriteLock` |
| Router accepts routeLen 0 (`< 0` instead of `< 1`) | KILLED | `ShardAndSizeTest.constructorValidation` |
| Tie rule reversed; raise leaves the old entry; raise not cut to K; empty branches not cut; fuzzy loses the on-query flag; fuzzy-only ranked above exact; writer without write lock; underflow check dropped; short prefix routed to one shard (earlier round) | KILLED | `TrieTest`, `DifferentialTest`, `FuzzyTest`, `ConcurrencyTest`, `ShardAndSizeTest` |

Mutants that survive because they do not change behaviour (equivalent), each probed and judged:

| Mutation | Result | Why it is equivalent |
|---|---|---|
| Drop the `k == 0` guard in `suggest` | SURVIVED | `k = 0` then copies `subList(0, 0)`, which is empty anyway |
| Drop the early return in fuzzy when exact matches already fill k | SURVIVED | the later `need = k - size` is 0, so nothing is added |
| Drop the `i >= capacity` return in `upsert` | SURVIVED | the next lines add then trim back to K, same final list |
| `s.length() >= routeLen` in `shardFor` | SURVIVED | a word of exactly routeLen letters is its own key either way |
| `floorMod` replaced by `& 0x7fffffff` then `%` | SURVIVED | a different but equally valid spread; the routing rule only needs consistency |
| `Math.min(capacity + 0, ...)` | SURVIVED | arithmetic no-op, listed only to show the probe harness works |

## 4. Gates

```bash
mvn -o -pl 40-autocomplete-top-k test-compile | rg WARNING      # prints nothing
python3 scripts/check_style.py 40-autocomplete-top-k           # 0 problem(s)
```
