# Topic 23: Delayed Job Scheduler, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

All tests run on virtual time, so they finish in well under a second.

```bash
mvn -q -pl 23-delayed-job-scheduler test                                    # all tests (88 run, 1 benchmark skipped)
mvn -q -pl 23-delayed-job-scheduler test -Dtest=CancelRaceTest              # the cancel-versus-fire race
mvn -q -pl 23-delayed-job-scheduler test -Dtest=DifferentialTest            # wheel versus heap on random programs
mvn -q -pl 23-delayed-job-scheduler test -Dtest='SchedulerContractTest#fixedRateKeepsTheGridAndFixedDelaySlips'
```

`-q` means quiet: no output means every test passed.

## 2. Run the heap versus wheel benchmark (off by default)

```bash
mvn -q -pl 23-delayed-job-scheduler test -Dtest=ScaleBenchmarkTest -Dscheduler.bench=true -DargLine="-Dscheduler.bench=true"
```

It takes about a minute. The parent pom fixes `argLine` to `-ea`, so the JVM heap is the machine default; the benchmark prints it. Output from one run on an Apple M3 Max, JDK 21.0.8 (best of 5 after 3 warm-ups, milliseconds):

```
max heap (Runtime.maxMemory) = 24576 MiB
n,heap_ms,wheel_ms
1000,0.8,1.4
10000,2.6,1.3
100000,46.8,18.4
1000000,893.5,628.2
```

Results vary between runs (at 1M the wheel ranged 359 to 717 ms over three runs). See the README for all three.

## 3. Style gate

```bash
python3 scripts/check_style.py 23-delayed-job-scheduler    # prints "0 problem(s)"
```
