# Topic 18: Logging Framework, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring, no REST)

```bash
mvn -q -pl 18-logging-framework test                                          # all 53 tests, about 2 to 2.5 seconds wall time including Maven start-up
mvn -q -pl 18-logging-framework test -Dtest=AsyncAppenderTest                 # only the bounded queue and drop policy tests
mvn -q -pl 18-logging-framework test -Dtest=MdcTest                           # only the MDC across threads tests
mvn -q -pl 18-logging-framework test -Dtest='LoggerTest#brokenAppenderIsIsolatedAndCounted'   # one test
```

`-q` means quiet: no output means every test passed.

## Gates

```bash
mvn -o -pl 18-logging-framework test-compile | rg WARNING      # prints nothing
python3 scripts/check_style.py 18-logging-framework            # 0 problem(s)
```

## Measured (this machine, JDK 21.0.8, Apple silicon, 3 runs)

| What | Result |
|---|---|
| Full test run, wall time | 2.54 s, 2.01 s, 2.05 s |
| Disabled-level calls: 200,000 calls of `debug("constant")` and `event(level).field("k", 1).log("constant")` | measured 0 bytes allocated on the calling thread; the test asserts the bound of 4 KB (4,096 bytes), not the measurement. Capturing lambdas and large boxed values are not covered |
