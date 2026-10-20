# Topic 07: Concurrency Primitives — Commands

Run from `low-level-designs/` with `JAVA_HOME` pointing at a JDK 21+.

```bash
mvn -q -pl 07-concurrency-primitives test                          # all 6 tests (3 bugs reproduced, 3 fixes proven)
mvn -q -pl 07-concurrency-primitives test -Dtest=SeatMapTest       # the deterministic (barrier-forced) race
mvn -q -pl 07-concurrency-primitives test -Dtest='HitCounterTest#bugVolatileIncrementLosesUpdates'

# Re-run the probabilistic tests a few times; the fix tests must ALWAYS be green
for i in 1 2 3 4 5; do mvn -q -pl 07-concurrency-primitives test || break; done

# See a thread dump of a live JVM (useful when a race test hangs): find the pid, then dump it
jps -l
jcmd <pid> Thread.print
```

Why force the interleaving? Measured on an Apple-silicon laptop, 20 runs each, the broken
`CheckThenActSeatMap` **without** the barrier hook (`RaceWindow.NONE`):

| Setup | Runs that double-booked |
|---|---|
| 2 threads, 1 seat (the "obvious" race test) | **2 of 20**, so this test passes 90% of the time against broken code |
| 32 threads × 1,000 seats | 20 of 20 (66 to 1,170 extra "winners" per run) |

A small race test that passes proves almost nothing. Either force the interleaving at a seam
(deterministic) or apply enough contention that the fix test's exact assertion means something.
