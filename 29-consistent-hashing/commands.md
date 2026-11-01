# Topic 29: Consistent Hashing, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests (core only, no Spring)

```bash
mvn -q -pl 29-consistent-hashing test                                          # all 48 tests, about 2 seconds
mvn -q -pl 29-consistent-hashing test -Dtest=RingLookupTest                    # differential lookup, wrap-around, collisions, replication
mvn -q -pl 29-consistent-hashing test -Dtest=MovementTest                      # movement property and the moved-fraction table
mvn -q -pl 29-consistent-hashing test -Dtest=DistributionTest                  # vnode spread table and weight shares
mvn -q -pl 29-consistent-hashing test -Dtest=SnapshotTest                      # frozen writer/reader, racing writers
mvn -q -pl 29-consistent-hashing test -Dtest=Hash64Test                        # published MurmurHash3 vectors
python3 scripts/check_style.py 29-consistent-hashing                           # style gate: expect "0 problem(s)"
```

No output from `mvn -q` means every test passed. The measurement tests print their tables on standard output with the prefixes `MOVEMENT`, `DIST` and `WEIGHT`; run without `-q` to see them:

```bash
mvn -pl 29-consistent-hashing test -Dtest='MovementTest,DistributionTest' | rg '^(MOVEMENT|DIST|WEIGHT)'
```

Sample output (JDK 21.0.8, the run quoted in the README):

```
MOVEMENT 10 -> 11 | 0.0909 | 0.0872 | 0.0908 | 0.9098
DIST 1 | 0.8121 | 2.776
DIST 1000 | 0.0369 | 1.060
WEIGHT RING heavy share=0.6205 want=0.6250
```
