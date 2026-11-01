# Topic 14: Parking Lot, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core module only, no Spring)

```bash
mvn -q -pl 14-parking-lot test                                         # all 22 tests
mvn -q -pl 14-parking-lot test -Dtest=ConcurrencyTest                  # only the race tests
mvn -q -pl 14-parking-lot test -Dtest='ParkingLotTest#holdIsExpiredAtTheBoundaryInstant'   # one test
```

`-q` means quiet: no output means every test passed.

## Real output (JDK 21.0.8, Apple-silicon laptop, one run; Maven reported 0.9 s total, which includes build tooling, so treat it as roughly one second)

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.lld.parkinglot.ConcurrencyTest
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in com.lld.parkinglot.ParkingLotTest
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
```

## Style check

```bash
python3 scripts/check_style.py 14-parking-lot    # prints "0 problem(s)"
```
