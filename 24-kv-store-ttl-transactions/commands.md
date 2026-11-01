# Topic 24: KV Store with TTL and Transactions, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer. There is no REST layer, so there is nothing to start.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## Run the tests

```bash
mvn -q -pl 24-kv-store-ttl-transactions test                                   # all 48 tests, about 1 second; no output means all passed
mvn -q -pl 24-kv-store-ttl-transactions test -Dtest=TransactionTest            # nested begin/commit/rollback only
mvn -q -pl 24-kv-store-ttl-transactions test -Dtest=ConcurrencyTest            # first-committer-wins and the exact-total counter
mvn -q -pl 24-kv-store-ttl-transactions test -Dtest=DifferentialTest           # 20 seeds x 3000 random ops against the naive model
mvn -q -pl 24-kv-store-ttl-transactions test -Dtest='WalTest#tornLastRecordIsIgnored'   # one test method
```

## Check the gates

```bash
mvn -o -pl 24-kv-store-ttl-transactions test-compile | rg WARNING     # prints nothing: zero -Xlint warnings
python3 scripts/check_style.py 24-kv-store-ttl-transactions            # 0 problem(s)
```

## Test count per class (measured on JDK 21.0.8, Apple silicon, 3 green runs in a row)

| Class | Tests | What it proves |
|---|---|---|
| `ExpiryTest` | 8 | half-open boundary, lazy expiry, bounded sweep, plain set clears TTL |
| `TransactionTest` | 14 | rollback restores values and TTLs, inner commit merges up, session close, write skew, TTL from the call |
| `SnapshotTest` | 9 | isolation, expiry rule, version GC, double close, sweep under a snapshot |
| `GcCostTest` | 2 | GC does not scan all keys on every transaction end |
| `ConcurrencyTest` | 7 | forced conflict, expiry vs user write, exact total 8 x 200 = 1600 |
| `DifferentialTest` | 1 | random ops, second session and snapshots match the naive model |
| `WalTest` | 7 | replay, torn tail, fail-stop, recovery handoff |

Whole module: 48 tests, about 1.2 s wall time including Maven start-up.
