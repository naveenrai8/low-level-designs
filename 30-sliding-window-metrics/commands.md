# Topic 30: Sliding-Window Metrics, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring)

```bash
mvn -q -pl 30-sliding-window-metrics test                                          # all tests, about 3 seconds (54 tests)
mvn -q -pl 30-sliding-window-metrics test -Dtest=WindowRotationTest                # boundary tick, idle gaps
mvn -q -pl 30-sliding-window-metrics test -Dtest=ConcurrencyTest                   # forced races and hammers
mvn -q -pl 30-sliding-window-metrics test -Dtest=PercentileAccuracyTest            # prints the MEASURED lines
python3 scripts/check_style.py 30-sliding-window-metrics                           # style gate: expect "0 problem(s)"
```

No output from `mvn -q` means every test passed (the accuracy test prints its MEASURED lines).

## Recorded accuracy output

```
MEASURED uniform p0.5 exact=499301 got=497664 err=0.3279% p0.99 exact=990184 got=987136 err=0.3078% p0.999 exact=999139 got=995328 err=0.3814%
MEASURED lognormal p0.5 exact=8101 got=8096 err=0.0617% p0.99 exact=130842 got=130560 err=0.2155% p0.999 exact=321054 got=321536 err=0.1501%
MEASURED exponential p0.5 exact=3463 got=3472 err=0.2599% p0.99 exact=23010 got=22912 err=0.4259% p0.999 exact=34000 got=34048 err=0.1412%
MEASURED bimodal p0.5 exact=2013 got=2008 err=0.2484% p0.99 exact=433332 got=432128 err=0.2778% p0.999 exact=482219 got=481280 err=0.1947%
```
