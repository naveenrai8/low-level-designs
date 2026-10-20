# Topic 21: Rate Limiter — Commands

All commands run from `low-level-designs/` with Java 21 and Maven 3.9+.

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+

# --- core module only (no Spring) ---------------------------------------------
mvn -q -pl 21-rate-limiter test                                       # all 45 tests, ~1s
mvn -q -pl 21-rate-limiter test -Dtest=ConcurrencyTest                # just the race tests
mvn -q -pl 21-rate-limiter test -Dtest='AlgorithmBehaviourTest$TokenBucketBehaviour'   # one nested class
mvn -q -pl 21-rate-limiter test -Dtest='RateLimiterContractTest#evictionIsLossless'    # one method

# --- the HTTP adapter (the single Spring Boot app) ------------------------------
mvn -q -pl app -am package -DskipTests             # -am builds 21-rate-limiter first
java -jar app/target/app-1.0.0-SNAPSHOT.jar        # port 8080; 5/s token bucket (app/src/main/resources/application.yml)

# in another terminal
for i in $(seq 1 7); do
  curl -s -o /dev/null -w '%{http_code}  remaining=%header{x-ratelimit-remaining}  retry-after=%header{retry-after}\n' \
       -X POST localhost:8080/api/rate-limit/alice
done
# verified output:
#   200  remaining=4 … 200  remaining=0      five grants (the burst)
#   429  remaining=0  retry-after=1          exact wait is ~200ms; HTTP Retry-After is whole seconds, rounded UP

curl -s -X POST localhost:8080/api/rate-limit/bob -o /dev/null -w '%{http_code}\n'    # 200: keys are independent
curl -s -X POST 'localhost:8080/api/rate-limit/alice?permits=99' | jq .               # 400 ProblemDetail: can never succeed

# switch algorithm / limit without code changes (any Spring property works as --arg)
java -jar app/target/app-1.0.0-SNAPSHOT.jar --lld.rate-limit.algorithm=SLIDING_WINDOW_LOG --lld.rate-limit.permits=2
```

Notes:
- `curl -w '%header{…}'` needs curl ≥ 7.84. On older curl, use `curl -si … | rg -i 'HTTP/|retry-after|x-ratelimit'`.
- If you change compiler flags in the root `pom.xml`, run `mvn clean …`. A pom-only change doesn't
  recompile unchanged classes (see SpringBoot-poc Topic 19 §3.8).
