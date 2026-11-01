# Topic 28: ID Generator, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring, no REST adapter)

```bash
mvn -q -pl 28-id-generator test                                                    # all tests, about 1 second
mvn -q -pl 28-id-generator test -Dtest=SnowflakeIdGeneratorTest                    # ordering, overflow, step-back, races
mvn -q -pl 28-id-generator test -Dtest=SnowflakeIdGeneratorTest#stepBackBoundaryIsExact   # one test
mvn -q -pl 28-id-generator test -Dtest='UuidV7GeneratorTest,UlidGeneratorTest'     # the sortable string IDs
python3 scripts/check_style.py 28-id-generator                                     # style gate: expect "0 problem(s)"
```

No output from `mvn -q` means every test passed.

## Throughput (opt-in benchmark, off by default)

```bash
mvn -q -pl 28-id-generator test -Didgen.bench=true -Dtest=ThroughputBenchmarkTest   # prints BENCH lines
```

Setup of the recorded run: Apple M3 Max (14 cores), JDK 21.0.8, real `Clock.systemUTC()`, spinning waiter, 2,000,000 IDs per thread, 3 rounds.

| Threads | IDs per second (range over 3 rounds) |
|---|---|
| 1 | about 4.09 to 4.10 million |
| 8, one shared generator | about 3.4 to 3.5 million in total |

Both numbers are bound by the cap of 4096 IDs per millisecond per node (4.096 million per second), not by the CPU. The single thread sits right at the cap. The 8-thread drop below the cap is CAS contention. One machine, one JVM, no isolation: treat it as an order of magnitude.
