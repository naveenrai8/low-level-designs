# Topic 47: Capstone, Ticketing Platform, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer. The module reuses Topics 31, 32 and 44, so they must be installed in the local Maven repository once (`mvn -o -q -pl 31-seat-hold-booking,32-double-entry-ledger,44-notification-service -am install -DskipTests`).

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 47-capstone-ticketing-platform test                                # 54 test runs, about 1 s; no output means green
mvn -o -q -pl 47-capstone-ticketing-platform test -Dtest=RoundTwoTest            # the crash at every named point
mvn -o -q -pl 47-capstone-ticketing-platform test -Dtest=EndToEndTest            # the whole evening in virtual time
```

Measured (JDK 21.0.8, Apple silicon laptop, 3 runs): `BUILD SUCCESS`, 54 tests, about 1 s wall each. Per class: RoundOneTest 5, RoundTwoTest 22 (one method runs once per happy-path crash point), RoundTwoHardeningTest 19, RoundThreeTest 7, EndToEndTest 1.

## 2. Gates

```bash
mvn -o -pl 47-capstone-ticketing-platform test-compile | rg WARNING              # prints nothing
python3 scripts/check_style.py 47-capstone-ticketing-platform                    # 0 problem(s)
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now\(' 47-capstone-ticketing-platform/src   # prints nothing
```

## 3. Mutation self-check

Each row is one one-line change, compiled and run twice, with the source restored and byte-compared after every mutant. "Killed (n)" means n tests failed. The script is `/tmp/g47/mut.py` (1 s pause around every rewrite, because rewriting a file right before `mvn` can race with Maven's staleness check). M1 to M16 are the first set, S1 to S12 were found surviving by review (each was killed 0 of 2 before the tests in `RoundTwoHardeningTest` were added), N1 to N12 cover the new code. The control must be killed, and it is.

| # | One-line mutant | Run 1 | Run 2 |
|---|---|---|---|
| M1 | compensation skips the payment-hold void | killed (5) | killed (5) |
| M2 | void call replaced by a read (loser's hold never released) | killed (5) | killed (5) |
| M3 | compensation keeps the seats held | killed (2) | killed (2) |
| M4 | capture guard ignores an already-captured hold | killed (1) | killed (1) |
| M5 | capture even when the seat hold expired | killed (6) | killed (6) |
| M6 | notification sent before the confirm is recorded | killed (8) | killed (8) |
| M7 | notification key not derived from the checkout id | killed (3) | killed (3) |
| M8 | refund skips the ledger reversal | killed (3) | killed (3) |
| M9 | refund does not close Topic 31's refund note | killed (1) | killed (1) |
| M10 | payment hold lives no longer than the seat hold | killed (2) | killed (2) |
| M11 | outbox flag never cleared after submit | killed (3) | killed (3) |
| M12 | same key with a different request accepted | killed (2) | killed (2) |
| M13 | sweep picks live checkouts instead of expired | killed (5) | killed (5) |
| M14 | replay flag always false | killed (5) | killed (5) |
| M15 | any ledger error becomes DECLINED | killed (1 (after adding `otherLedgerErrorsAreNotDeclines`; it survived 0/2 before)) | killed (1) |
| M16 | authorize amount off by one | killed (36) | killed (36) |
| S1 | authorize without a time limit | killed (3) | killed (3) |
| S2 | grace is 2 seconds | killed (1) | killed (1) |
| S3 | no live check before authorize | killed (1) | killed (1) |
| S4 | not-found hold treated as refundable | killed (1) | killed (1) |
| S5 | flush counts every waiting record | killed (1) | killed (1) |
| S6 | replay only for CONFIRMED | killed (1) | killed (1) |
| S7a | REJECTED reason dropped | killed (2) | killed (2) |
| S7b | compensate reason dropped | killed (2) | killed (2) |
| S8 | confirm uses the key as payment ref | killed (1) | killed (1) |
| S9 | no in-flight lock (blocks instead of 409) | killed (1) | killed (1) |
| S10 | any reverse error rethrown (ALREADY_REVERSED not tolerated) | killed (1) | killed (1) |
| S11 | sweep without the hold-null check | killed (1) | killed (1) |
| S12a | zero amount accepted | killed (1) | killed (1) |
| S12b | zero payment grace accepted | killed (1) | killed (1) |
| N1 | compensate does not tolerate a forgotten hold | killed (2) | killed (2) |
| N2 | sweep without the per-record catch | killed (1) | killed (1) |
| N3 | not-found or already-confirmed refunds instead of NEEDS_REVIEW | killed (2) | killed (2) |
| N4 | flushOutbox waits for a busy record | killed (1) | killed (1) |
| N5 | purge ignores the retention | killed (1) | killed (1) |
| N6 | purge forgets records with a waiting message | killed (1) | killed (1) |
| N7 | duplicate seats accepted | killed (1) | killed (1) |
| N8 | seats not sorted | killed (1) | killed (1) |
| N9 | void rethrows an already-voided hold | killed (2) | killed (2) |
| N10 | `notification waiting` reason never cleared | killed (1) | killed (1) |
| N12 | void rethrows an expired payment hold (drop the `HOLD_EXPIRED` tolerance) | killed (1) | killed (1) |
| CONTROL | authorize amount + 1 (must be killed) | killed (3) | killed (3) |

One more mutant, "purge ignores unfinished records" (drop `rec.state.isTerminal()`), survives and is equivalent: `old` needs `finishedAt`, which is only set when the saga reaches an end state, so the check is redundant. It is kept in the code as a readable guard.

## 4. What the output shows

A crash at each named point, then one retry, converges (the parameterised runs of `crashAtAnyPointThenRetryConverges`; the three rollback points and the authorize gap have their own tests): `BEFORE_SEAT_HOLD`, `AFTER_SEAT_HOLD`, `AFTER_PAYMENT_HOLD`, `AFTER_CAPTURE_BEFORE_SAVE`, `AFTER_CAPTURE`, `AFTER_CONFIRM_BEFORE_SAVE`, `AFTER_CONFIRM`, `BEFORE_NOTIFY`, `AFTER_NOTIFY_SUBMIT`. Each ends with one booking, one capture, one delivered email, the venue holding 5000 and a ledger that sums to 0.
