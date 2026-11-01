# Topic 44: Notification Service, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests

```bash
mvn -q -pl 44-notification-service test                          # 53 tests, about 1 second, no output means green
mvn -q -pl 44-notification-service test -Dtest=QuietHoursTest     # only the quiet-hours and DST tests
mvn -q -pl 44-notification-service install                        # needed before the app can see this module
mvn -q -pl app test                                               # app tests (REST), including NotificationControllerTest
python3 scripts/check_style.py 44-notification-service            # must print 0 problem(s)
```

Measured on a laptop with JDK 21.0.8, 3 runs in a row: 53 tests green, about 1 s each. The app's `NotificationControllerTest` was run alone 10 times, all green. The core tests use a virtual clock, so nothing sleeps.

## 2. Try the API by hand

```bash
mvn -q -pl app -am package -DskipTests
java -jar app/target/app-1.0.0-SNAPSHOT.jar
# in a second terminal
curl -i -X POST localhost:8080/notifications -H 'Content-Type: application/json' -H 'Idempotency-Key: k1' \
  -d '{"userId":"alice","channel":"SMS","template":"otp","variables":{"code":"123456"}}'     # 202 with an id
curl -i localhost:8080/notifications/<id>                                                    # status
```

Repeat the first call: 200 with the same id. Change the code and keep the key: 422. Drop the header: 400.

## 3. Mutation self-check

Each row is a one-line bug put into the code. A test must fail. All mutants compiled except where noted (a compile error is a false kill). M1 to M20 were the first round. S1 to S12 were mutants that survived the first round (found in review); N1 to N7 cover the review fixes.

| # | One-line bug | Killed by |
|---|---|---|
| M1 | quiet-hours end is inclusive | halfOpenEdges, sameDayWindow |
| M2 | midnight-crossing uses AND instead of OR | endIsTheNextSevenAm, evaluatedInTheUsersZone, fallBackOverlap |
| M3 | DST gap resolved with ZonedDateTime.of (shifts an hour) | springForwardGap, heldAcrossSpringForward |
| M4 | DST overlap takes no preferred offset | fallBackOverlap |
| M5 | evening end is the same day | endIsTheNextSevenAm, heldAcrossSpringForward |
| M6 | HTML escape does the ampersand last (double escape) | emailEscapesHtml, escapingIsPerChannel |
| M7 | missing variable becomes blank | missingVariableIsAnError |
| M8 | SMS limit off by one | smsLimit |
| M9 | same key with another payload is not rejected | sameKeyDifferentPayloadConflicts |
| M10 | idempotency check is get-then-put instead of computeIfAbsent | concurrentDuplicatesProduceOneSend |
| M11 | permanent failure is retried | permanentFailureGoesStraightToDlq |
| M12 | one attempt too many before the DLQ | retriesExhaustedGoToDlq, attemptsNeverExceedTheLimit |
| M13 | rate limit skipped | rateLimitDefersInsteadOfDropping, rateLimitIsPerUser |
| M14 | urgent obeys quiet hours | urgentBypassesQuietHours |
| M15 | opt-out ignored at send time | optOutAfterSubmitIsHonoured, optOutSuppressesEvenUrgent |
| M16 | no jitter | backoffIsDeterministicAndCapped (varies with seed) |
| M17 | no cap on backoff | backoffIsDeterministicAndCapped |
| M18 | a rate-limit deferral costs an attempt | rateLimitDefersInsteadOfDropping |
| M19 | a rate-limited message is dropped | rateLimitDefersInsteadOfDropping, rateLimitIsPerUser |
| M20 | quiet hours drop the message | quietHoursDefersToTheEnd |
| S1 | `m.appendTail(out)` removed from Template | textAfterThePlaceholderIsKept |
| S2 | same-day window end treated as tomorrow | sameDayWindowEndsToday |
| S3 | DLQ lists every non-pending message | dlqHoldsOnlyDeadMessages |
| S4 | due messages sorted newest first | olderDueTimeGoesFirstUnderPressure |
| S5 | deleted user not suppressed (NoSuchElement) | userDeletedAfterSubmitIsSuppressed |
| S6 | null priority defaults to URGENT | nullPriorityIsHeldInQuietHours |
| S7 | SMS cut keeps the tail instead of the start | smsCutKeepsTheStart |
| S8 | push escapes HTML | pushLeavesValuesRaw |
| S9 | Location header loses the id (app) | submitIs202AndGetShowsStatus (now asserts Location) |
| S10 | RetryPolicy accepts maxAttempts 0 | retryPolicyChecksItsArguments |
| S11 | RetryPolicy accepts a zero base | retryPolicyChecksItsArguments |
| S12 | null idempotency key gives NullPointerException | nullKeyIsInvalidRequest |
| N1 | unexpected transport exception escapes runDue | crashingTransportDoesNotBlockOthers (first try did not compile; redone with a compiling catch) |
| N2 | missing address sent as null | missingAddressAtSendTimeIsDead |
| N3 | tie-break by id text instead of sequence | sameTimeMessagesGoOutInSubmitOrder |
| N4 | null variable accepted | nullVariablesAreRejected, app 400 test |
| N5 | emoji split at the SMS cut | smsCutDoesNotSplitAnEmoji |
| N6 | replay renders before the key lookup | replayWorksAfterTheUserIsGone |
| N7 | app answers 409 instead of 422 | replayIs200AndDifferentBodyIs422 |

The first jitter test did not fail on M16 (no jitter) because it only checked ranges; it now also checks that different seeds give different waits. The app test `rateLimitedMessagesAreDeferredThenSent` was flaky (5 of 6 runs) for two reasons: ids were random UUIDs used as the tie-break, and alice's rate-limit tokens were shared with other tests in the same Spring context. It now uses a sequence number, its own user, and a one-hour timer interval so the worker cannot race the test.
