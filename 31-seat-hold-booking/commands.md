# Topic 31: Seat-Hold Booking, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -o -q -pl 31-seat-hold-booking install                                   # core: 37 tests, a few seconds (install, because app/ depends on it)
mvn -o -q -pl 31-seat-hold-booking test -Dtest=MultiSeatHoldTest             # the deadlock bug, the sorted-lock fix, the hammer
mvn -o -q -pl 31-seat-hold-booking test -Dtest=SweepDifferentialTest         # sweep never changes an answer
mvn -o -q -pl app test -Dtest=BookingControllerTest                          # every HTTP status code, with a controllable clock
```

`-q` means quiet: no output means every test passed. `src/test/resources/junit-platform.properties` gives every test a 10 second timeout.

## 2. Gates

```bash
mvn -o -pl 31-seat-hold-booking,app test-compile | rg WARNING                # only the JDK agent notice, no javac warning
python3 scripts/check_style.py 31-seat-hold-booking app/src/main/java/com/lld/app/booking app/src/test/java/com/lld/app/booking   # 0 problem(s)
```

## 3. Try it with curl

```bash
mvn -o -q -pl app spring-boot:run &                                           # starts on :8080 with a show named demo (seats A1..A10)
curl -s -XPOST localhost:8080/api/booking/shows/demo/holds -H 'Content-Type: application/json' -d '{"seatIds":["A1","A2"]}'
curl -s -i -XPOST localhost:8080/api/booking/holds/HOLD_ID/confirm -H 'Content-Type: application/json' -H 'Idempotency-Key: k1' -d '{"paymentRef":"pay-1"}'   # 201, then the same call again gives 200
```

## 4. Mutation checks done while writing

Each change was made by hand, the tests were run, and the code was restored.

| Change | Test that failed |
|---|---|
| `sweep()` returns 0 at once | `SweepDifferentialTest` (sweep must really free things) |
| Controller drops the `AlreadyConfirmedException` catch | `confirmingABookedHoldWithANewKeyIs409` |
| Controller drops the paymentRef or seatIds check | `missingBodyFieldsAre400` |
| Replay draws a booking id | `replayDoesNotDrawANewBookingId` |
| Fingerprint is `holdId + "|" + paymentRef` | `fingerprintHasNoSeparatorCollision` |
| Lookup trusts front-of-queue order | `IdempotencyStoreTest.expiredEntryBehindALiveOneIsAbsent` |
| No key lock in confirm | `oneKeyCannotBookTwoHolds`, `concurrentSameKeyConfirmsMakeOneBooking` |
| Skip the seat-ownership check in confirm | `backwardClockStepCannotSellASeatTwice` |
| `refundDone` deletes the note | `paidRefundIsNotRecreatedAfterTheKeyExpired` |
| `lockAll` does not unlock when the hook throws | `locksAreReleasedWhenTheHookThrows` |
| Lock in request order | `sortedLockingTakesTheSmallestSeatFirstAndNeverPartiallySucceeds`, the hammer |
| Real seat named `OVERBOOK-1` accepted | `realSeatNamedLikeAnOverbookUnitIsRejected` |
| Expired confirm stores no outcome | `expiredConfirmWithDifferentPaymentIsAConflict` |
