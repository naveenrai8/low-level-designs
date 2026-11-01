# Topic 16: Elevator, Commands

Run every command from the `low-level-designs/` folder with Java 21 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64
mvn -q -pl 16-elevator test                                # all 39 tests, about 2 seconds; no output means all passed
mvn -q -pl 16-elevator test -Dtest=LookTest                # only the LOOK scheduling tests
mvn -q -pl 16-elevator test -Dtest=FireMaintenanceTest     # mode table, fire, maintenance
```

## Print the dispatcher comparison table

```bash
mvn -q -pl 16-elevator compile
$JAVA_HOME/bin/java -cp 16-elevator/target/classes com.lld.elevator.SimulationReport 42
```

Output (seed 42, 20 floors, 4 cars):

```
seed=42 riders=174
| Dispatcher | Riders | Mean wait | p95 wait | Max wait | Finished at tick |
|---|---|---|---|---|---|
| nearest | 174 | 12.5 | 40 | 47 | 622 |
| least-loaded | 174 | 7.5 | 26 | 34 | 636 |
| zoned | 174 | 16.2 | 37 | 42 | 622 |
```

Columns are riders, mean wait, p95 wait (nearest-rank), max wait and the tick count when the run ended (one more than the tick of the last delivery). Pass another seed as the argument to see the order change.
