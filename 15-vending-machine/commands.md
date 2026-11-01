# Topic 15: Vending Machine, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring)

```bash
mvn -q -pl 15-vending-machine test                                          # all 39 tests, about 2 seconds
mvn -q -pl 15-vending-machine test -Dtest=ChangeMakerTest                   # greedy failures + differential test
mvn -q -pl 15-vending-machine test -Dtest=TransitionTableTest               # all 24 state/event pairs
mvn -q -pl 15-vending-machine test -Dtest='VendingMachineTest#jamRefundsEverythingAndConservesMoney'
```

`-q` means quiet: no output means every test passed.

## Check the quality gates

```bash
mvn -o -pl 15-vending-machine test-compile | rg WARNING     # prints nothing: zero lint warnings
python3 scripts/check_style.py 15-vending-machine           # prints "0 problem(s)"
```
