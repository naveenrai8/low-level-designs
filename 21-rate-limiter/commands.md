# Topic 21: Rate Limiter, Commands

Run every command from the `low-level-designs/` folder. You need Java 21 or newer and Maven 3.9 or newer.

First, tell Maven which Java to use:

```bash
export JAVA_HOME=/Users/naveen.rai/java/openjdk_21.0.8.0.121_21.45.56_aarch64   # any JDK 21+
```

## 1. Run the tests (core module only, no Spring)

The core module is plain Java. These commands run its tests, either all of them or just one part.

```bash
mvn -q -pl 21-rate-limiter test                                                        # all 45 tests, about 1 second
mvn -q -pl 21-rate-limiter test -Dtest=ConcurrencyTest                                 # only the "many threads at once" tests
mvn -q -pl 21-rate-limiter test -Dtest='AlgorithmBehaviourTest$TokenBucketBehaviour'   # only the token bucket tests (a nested class)
mvn -q -pl 21-rate-limiter test -Dtest='RateLimiterContractTest#evictionIsLossless'    # one single test method
```

`-q` means quiet: no output means every test passed.

## 2. Start the HTTP server (the Spring Boot app)

This builds the app and starts it on port 8080. The default limit is 5 requests per second per user, using the token bucket. That setting lives in `app/src/main/resources/application.yml`.

```bash
mvn -q -pl app -am package -DskipTests             # -am also builds 21-rate-limiter, because the app needs it
java -jar app/target/app-1.0.0-SNAPSHOT.jar        # starts the server on port 8080
```

## 3. Send requests and watch the limit kick in

In a second terminal, send 7 requests in a row for the user `alice`:

```bash
for i in $(seq 1 7); do
  curl -s -o /dev/null -w '%{http_code}  remaining=%header{x-ratelimit-remaining}  retry-after=%header{retry-after}\n' \
       -X POST localhost:8080/api/rate-limit/alice
done
```

What you see (checked by running it):

| Requests | Answer | Why |
|---|---|---|
| 1 to 5 | `200`, `remaining` goes 4, 3, 2, 1, 0 | The bucket starts full with 5 tokens, and each request uses one |
| 6 and 7 | `429`, `remaining=0`, `retry-after=1` | The bucket is empty. The real wait is about 200 ms, but the HTTP `Retry-After` header only allows whole seconds, so it is rounded **up** to 1 |

Two more checks:

```bash
curl -s -X POST localhost:8080/api/rate-limit/bob -o /dev/null -w '%{http_code}\n'    # 200: bob has his own bucket, alice's requests don't affect him
curl -s -X POST 'localhost:8080/api/rate-limit/alice?permits=99' | jq .               # 400 with a ProblemDetail body: 99 is more than the limit of 5, so it could never succeed
```

## 4. Try another algorithm or limit without changing code

Any setting from `application.yml` can be overridden on the command line:

```bash
java -jar app/target/app-1.0.0-SNAPSHOT.jar --lld.rate-limit.algorithm=SLIDING_WINDOW_LOG --lld.rate-limit.permits=2
```

The choices for `algorithm` are `TOKEN_BUCKET`, `SLIDING_WINDOW_LOG` and `SLIDING_WINDOW_COUNTER`.

## Notes

- `curl -w '%header{…}'` needs curl 7.84 or newer. On an older curl, use `curl -si … | rg -i 'HTTP/|retry-after|x-ratelimit'` instead.
- If you change compiler flags in the root `pom.xml`, run `mvn clean …` first. A change to only the pom doesn't recompile classes that haven't changed, so the old ones would hide your change (see SpringBoot-poc Topic 19 §3.8).
