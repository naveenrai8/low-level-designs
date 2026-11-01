# Topic 32: Double-Entry Ledger, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 32-double-entry-ledger test                                  # all 54 tests, about 0.9 s of test time (2.5 to 3 s wall with Maven, 3 runs)
mvn -o -q -pl 32-double-entry-ledger test -Dtest=LedgerBasicsTest          # rules, rejections, immutability, reversal
mvn -o -q -pl 32-double-entry-ledger test -Dtest=InvariantTest             # 6000 seeded random operations, sum is always 0
mvn -o -q -pl 32-double-entry-ledger test -Dtest=HoldTest                  # authorise, capture, void, expiry
mvn -o -q -pl 32-double-entry-ledger test -Dtest=IdempotencyTest           # replays, conflict, bounded keys
mvn -o -q -pl 32-double-entry-ledger test -Dtest=ConcurrencyTest           # deadlock, hammer, check-then-act race
mvn -o -q -pl 32-double-entry-ledger test -Dtest=ProjectionTest            # rebuild and balanceAt
```

`-q` means quiet: no output means every test passed. `src/test/resources/junit-platform.properties` sets a 10 second default timeout. The race tests wait on a probe (a rival thread queued on a lock) or on the JVM's deadlock detector, never on a timer, so each takes milliseconds. Their safety bounds (5 to 10 s) are only reached when a test fails.

## 2. Gates

```bash
mvn -o -pl 32-double-entry-ledger test-compile | rg WARNING    # prints nothing
python3 scripts/check_style.py 32-double-entry-ledger          # 0 problem(s)
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now' 32-double-entry-ledger/src   # prints nothing
```

## 3. Mutation checks done while writing

Each change was made by hand, the suite was run, and the code was restored. Before the extra tests were added every one of these survived.

| Change | Test that failed |
|---|---|
| Delete the hold restore after a failed capture | `failedCaptureRestoresTheHold` |
| Delete `expireDue` in `capture` | `captureAtTheDeadlineIsExpired` |
| Delete `expireDue` in `doPost` | `postSeesExpiredHoldAsFree` |
| Delete `expireDue` in `authorize` | `authorizeSeesExpiredHoldAsFree` |
| Delete `expireDue` in `voidHold` | `voidOfAnExpiredHoldIsRejected` |
| Capture locks unsorted | `captureAndOpposingPostNeverDeadlock` |
| `addExact` to `+` on the balance | `balanceOverflowIsRejected` |
| Delete `slot.dead = true` | `failedFirstPosterDoesNotLeakADuplicate` |
| Idempotency bound `>` to `>=` | `keyMemoryIsBounded` |
| Disable the same-account capture check | `captureIntoTheSameAccountIsRejected` |
| `trialBalance` without locks | `readersAlwaysSeeConsistentBooks` |
| Disable the captured-retry return | `retriedCaptureReturnsTheOriginalEntry` |
