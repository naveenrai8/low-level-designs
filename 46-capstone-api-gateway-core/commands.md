# Topic 46 commands

All commands run from `low-level-designs/`. Use JDK 21 and offline Maven, and never run a root `clean`.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64
```

## Run the tests

The app depends on the core jar, so install the core first (and again after any core change).

```bash
mvn -o -q -pl 46-capstone-api-gateway-core install
mvn -o -q -pl app test
```

`-q` prints nothing when all is green. Drop `-q` to see `Tests run: 79` (core) and `Tests run: 32` (app, 10 of them gateway tests).

## Try it by hand

Start the app in one terminal, call it from a second terminal.

```bash
mvn -o -pl app spring-boot:run
```

```bash
curl -i -H 'X-API-Key: key-alice' localhost:8080/gw/orders/1
curl -i localhost:8080/gw/orders/1
```

## Style and hygiene gates

```bash
python3 scripts/check_style.py 46-capstone-api-gateway-core app/src/main/java/com/lld/app/gateway app/src/test/java/com/lld/app/gateway
mvn -o -pl 46-capstone-api-gateway-core test-compile | rg WARNING
rg -n 'Thread\.sleep|currentTimeMillis|Instant\.now\(' 46-capstone-api-gateway-core/src app/src/*/java/com/lld/app/gateway
```

All three print nothing but the style line `0 problem(s)`.

## Mutation table

Each row is a one-line change to production code, compiled, tests run 2 times, file restored (checked with cmp). S1 to S13 came from review, M from the first pass, N from the fixes.

| Mutant | Result | First failing test |
|---|---|---|
| S1 PATCH added to safe-to-retry | killed 2/2 | patchWithoutKeyIsNotRetried |
| S2 DELETE dropped from safe-to-retry | killed 2/2 | idempotentMethodsAreRetried |
| S3 504 no longer retried | killed 2/2 | gatewayErrorStatusesAreRetried |
| S4 unreachable no longer retried | killed 2/2 | unreachableBackendIsRetried |
| S5 breaker counts only 5xx answers | killed 2/2 | timeoutsAndUnreachableCountAsBreakerFailures |
| S6 per-try timeout ignored | killed 2/2 | firstCallGetsThePerTryTimeoutWhenDeadlineIsLonger |
| S7 retry wait ignored in deadline check | killed 2/2 | retryThatCannotFitBeforeTheDeadlineIsNotStarted |
| S8 budget keyed by route name | killed 2/2 | breakerOpeningInsideTheLoopStopsTheLoop |
| S9 anonymous pass erases the caller | killed 2/2 | laterAnonymousPassKeepsTheIdentifiedCaller |
| S10 Allow lists first method only | killed 2/2 | allowHeaderListsEveryMethodOfThePath |
| S11 adapter drops the body | killed 2/2 | bodyAndQueryReachTheUpstream |
| S12 adapter has no catch around the core | killed 2/2 | unexpectedCoreCrashIs500 |
| S13 retryAfterSeconds returns seconds+1 always | killed 2/2 | alreadyOpenBreakerGivesCircuitOpenWithRetryAfter |
| M1 limit runs before auth | killed 2/2 | authAnswersAreNeverReplacedByRateLimitAnswers |
| M2 limiter rejection counted as breaker failure | killed 2/2 | fullJourneyThroughAllThreeRounds |
| M3 POST retried without key | killed 2/2 | blankIdempotencyKeyDoesNotEnableRetries |
| M4 retry wait ignores the deadline | killed 2/2 | retryThatCannotFitBeforeTheDeadlineIsNotStarted |
| M5 attempt ignores an already-passed deadline | killed 2/2 | lateSleepDoesNotStartAnAttemptPastTheDeadline |
| M6 first listed route wins over deeper | killed 2/2 | leftmostLiteralDecidesBetweenMixedPatterns |
| M7 literal beats placeholder removed | killed 2/2 | leftmostLiteralDecidesBetweenMixedPatterns |
| M8 4xx counted as server failure | killed 2/2 | clientErrorsAreNotRetriedOrCounted |
| M10 missing scope answers 401 | killed 2/2 | authAnswersAreNeverReplacedByRateLimitAnswers |
| M11 wrong method answers 404 | killed 2/2 | allowHeaderListsEveryMethodOfThePath |
| M12 prefix matches part of a segment | killed 2/2 | prefixMatchesWholeSegmentsOnly |
| M13 one shared breaker | killed 2/2 | breakerOpeningInsideTheLoopStopsTheLoop |
| M14 plain 500 retried | killed 2/2 | status500IsNotRetried |
| M16 limiter reads a frozen clock | killed 2/2 | deadlineBoundsRetries |
| M17 rate-limit key ignores the route | killed 2/2 | routesHaveSeparateBuckets |
| M18 429 has no Retry-After | killed 2/2 | fourthRequestIs429WithRetryAfter |
| M20 unknown API key accepted | equivalent (survives) | - |
| N1 method checked before path depth (admin bypass) | killed 2/2 | wrongMethodOnDeepRouteIs405NotFallThrough |
| N2 dot-segments not resolved | killed 2/2 | dotSegmentsAreResolvedBeforeRouting |
| N3 encoded dot allowed | killed 2/2 | trickyPathsAreRejectedWith400 |
| N4 climbing above root allowed | killed 2/2 | trickyPathsAreRejectedWith400 |
| N5 API key forwarded to upstream | killed 2/2 | credentialsAreStrippedAndIdentityIsSetByTheGateway |
| N6 client-claimed X-Client-Id forwarded | killed 2/2 | credentialsAreStrippedAndIdentityIsSetByTheGateway |
| N7 body cap not enforced | killed 2/2 | oversizedBodyIs413 |
| N8 refused retry hides the last answer (always 504) | killed 2/2 | lateSleepDoesNotStartAnAttemptPastTheDeadline |
| N9 open breaker hides the last answer (always circuit open) | killed 2/2 | breakerOpeningInsideTheLoopStopsTheLoop |
| N10 blank Idempotency-Key enables retry | killed 2/2 | blankIdempotencyKeyDoesNotEnableRetries |
| N11 non-retried requests do not feed the budget | killed 2/2 | nonRetryableRequestsStillFeedTheBudget |
| N12 scope 401 has no challenge | killed 2/2 | scopeHook401CarriesAChallenge |
| N13 circuit-open 503 has no Retry-After | killed 2/2 | alreadyOpenBreakerGivesCircuitOpenWithRetryAfter |
| N14 HEAD not served by GET routes | killed 2/2 | headIsAllowedWhereGetIs |
| N15 same-shape routes allowed | killed 2/2 | sameShapeDifferentParamNamesIsRejected |
| N16 unidentified caller reaches the limiter | killed 2/2 | unidentifiedCallerIsRejectedBeforeTheLimiter |
| N17 hop-by-hop headers forwarded | killed 2/2 | hopByHopAndCredentialHeadersAreNotForwarded |
| N18 Connection-named headers kept | killed 2/2 | hopByHopAndCredentialHeadersAreNotForwarded |
| N19 query string dropped by adapter | killed 2/2 | bodyAndQueryReachTheUpstream |
| N20 body read without a cap | equivalent (survives) | - |
| N21 route name with pipe allowed | killed 2/2 | routeNameWithPipeIsRejected |
| M20b unknown key becomes alice | killed 2/2 | unknownKeyIs401 |
| C1 control: any 5xx retried | killed 2/2 | status500IsNotRetried |
| C2 control: adapter returns fixed 200 | killed 2/2 | unexpectedCoreCrashIs500 |

Two survivors are equivalent mutants, not test gaps:

- M20 (`if (p == null)` made `if (false)`): the hook then passes a null principal, and the gateway turns a null principal into 401 anyway, so behaviour is identical. M20b mutates the real lookup and is killed.
- N20 (adapter reads without the cap): the 413 answer is the same, only memory use differs. A body of Integer.MAX_VALUE bytes is not testable in a unit test.

## Lessons

- A check that looks dead can matter. The deadline check in `attempt` seemed unreachable with exact sleeps, but a late-ending sleep reaches it. Test it with an `overshoot` clock (`lateSleepDoesNotStartAnAttemptPastTheDeadline`).
- Mutate the thing, not its guard. M20 survived because another layer also rejected; the real mutant was the key lookup.
- Mutation scripts live in `/tmp` in a private folder; a shared file name got overwritten by another run.
