# Topic 33: Splitwise, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring)

```bash
mvn -q -pl 33-splitwise test                                   # all 39 tests, well under 2 seconds
mvn -q -pl 33-splitwise test -Dtest=SplitStrategyTest          # equal/exact/percent/shares + seeded property test
mvn -q -pl 33-splitwise test -Dtest=DebtsTest                  # greedy, counterexample, DP vs brute force
mvn -q -pl 33-splitwise test -Dtest=GroupTest                  # balances, FX, history, rebuild, concurrency
python3 scripts/check_style.py 33-splitwise                    # style gate: expect "0 problem(s)"
```

No output from `mvn -q` means every test passed.

## The counterexample by hand

Nets `-9, -8, +2, +7, +8` (cents). Greedy pairs 9 with 8 (1 left), then 8 with 7 (1 left), then the two leftover 1s with the +2: 4 transfers. The best answer is 3: `{-9, +2, +7}` settles in 2 and `{-8, +8}` in 1. `DebtsTest.greedyIsNotOptimalOnCounterexample` asserts 4 versus 3.
