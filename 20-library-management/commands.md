# Topic 20: Library Management, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests

```bash
mvn -q -pl 20-library-management test                                             # all 37 tests, about 2 seconds
mvn -q -pl 20-library-management test -Dtest=ConcurrencyTest                      # only the racing tests
mvn -q -pl 20-library-management test -Dtest='LibraryTest#fineCapBoundary'        # one test
```

`-q` means quiet: no output means every test passed.

## Check the gates

```bash
mvn -o -pl 20-library-management test-compile | rg WARNING     # prints nothing
python3 scripts/check_style.py 20-library-management           # 0 problem(s)
```

## See the race test catch the bug

In `InMemoryLibrary.checkout`, replace `c.state.compareAndSet(s, next)` with a plain `c.state.set(next)` that returns true. Then `ConcurrencyTest.lastCopyRaceExactlyOneWins` fails with `expected: <1> but was: <2>` (two loans for one copy). Restore the CAS and it passes again.

Other mutations the suite catches: the loan slot CAS replaced by `incrementAndGet` fails `lastLoanSlotRaceExactlyOneWins`; the transfer CAS replaced by a plain `set` fails `racingTransfersOfOneCopyExactlyOneWins`; late days computed as a ceil of real hours over 24 fails `daysLateCountsLocalDaysAcrossDstEnd`.
