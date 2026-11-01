# Topic 46: Capstone, API Gateway Core

An API gateway is the front door of a system: every client request comes in here, and the gateway decides who may pass, how often, and whether the service behind it is healthy enough to be called. This capstone is a timed mock interview in 3 escalating rounds. It glues together [Topic 21 (rate limiter)](../21-rate-limiter/) and [Topic 27 (circuit breaker and retry)](../27-circuit-breaker-and-retry/) and adds routing and an auth hook. It writes no new limiter and no new breaker.

## 0. Words used in this topic

- **Gateway**: one program in front of many backend services. Clients only talk to it.
- **Upstream**: a backend service the gateway forwards to (orders, billing).
- **Route**: one row of the routing table: methods + path pattern + which upstream + which permission is needed.
- **Auth hook**: a small check that runs before the request is forwarded. 401 means "I do not know who you are". 403 means "I know who you are, and you may not".
- **Rate limit**: a cap on how many requests one client may make in a time window. Over the cap gives 429 with a `Retry-After` header (seconds to wait).
- **Circuit breaker**: a switch per upstream. After too many failures it opens and answers 503 at once, without calling the sick backend. After a wait it lets one probe through (half-open).
- **Idempotent**: doing it twice has the same effect as once. GET, PUT, DELETE are. POST is not, unless the client sends an `Idempotency-Key` so the backend can spot repeats (see [Topic 11](../11-money-ids-idempotency/)).
- **Deadline**: the most total time one request may take, retries included.
- **Virtual time**: tests move a fake clock instead of waiting (see [Topic 10](../10-time-and-testability/)).

## 1. The Problem

### What breaks without a gateway

- Every service repeats auth, limits and retries, each slightly different and some wrong.
- One greedy client uses all the capacity and everyone else gets errors.
- One sick backend makes every caller wait for a timeout. The waiting threads pile up and the healthy services die too.
- A retry of a payment POST charges the customer twice.

### What the interviewer is really probing

Not "can you write a router". They want to see whether you can compose parts you already own, in the right order, and defend the order. The real test is the ordering questions: what runs first, what a rejected request must NOT touch, and what counts as a failure.

### v1 requirements (say these out loud)

- Route by most specific path, then check the method. 404 for no route, 405 (with `Allow`) for a wrong method.
- Auth chain before anything expensive: API key (401), scope (403).
- Per-client, per-route rate limit with 429 and an honest `Retry-After`.
- One circuit breaker per upstream. Retry only safe requests, with a retry budget, bounded by a per-request deadline.

### Clarifying questions (with the answer I assume)

- Is the limit per API key or per IP? Per client id + route. The key identifies the client; the secret itself is never a limiter key.
- Are upstreams real HTTP? An `Upstream` interface with in-memory fakes; a real HTTP client is an adapter.

### Explicitly out of scope

Service discovery, load balancing, request transformation, TLS, caching, streaming, hot config reload.

## 2. How It Solves It: the 3 rounds

Each round adds one idea, one code delta, and one test file that proves it. Round 1 ships a working gateway; rounds 2 and 3 only insert steps into the same pipeline.

### Round 1 (0 to 15 min): routing and the auth hook

**Interviewer says:** "Design a gateway that sends `/orders/**` to the orders service and `/billing/**` to billing. Only known clients may call it."

**A strong candidate** draws the request flowing left to right, and names the interfaces first:

```
client -> [clean path] -> [route lookup] -> [auth chain] -> [upstream.handle()] -> client
            400 / 413        404 / 405         401 / 403
```

Worked example of the lookup, before any code. Routes: `/api` (GET), `/api/orders` (GET), `/users/{id}` (GET), `/users/me` (GET), `/admin` (GET, needs scope admin), `/` (GET, POST, DELETE, no scope).

| Request | Path matches | Most specific | Result | Why |
|---|---|---|---|---|
| GET /api/orders/7 | `/api`, `/api/orders`, `/` | `/api/orders` | route found | deeper prefix wins |
| GET /users/me | `/users/{id}`, `/users/me`, `/` | `/users/me` | route found | same depth, fixed text beats a placeholder |
| GET /ordersX | `/` only | `/` | catch-all | prefixes match whole segments only |
| DELETE /admin/users/7 | `/admin`, `/` | `/admin` | **405**, `Allow: GET, HEAD` | the most specific path decides; DELETE must NOT fall through to `/` |

The last row is the Round 1 trap. If the method were checked first, `/admin` (GET only) would be skipped for a DELETE, the catch-all `/` would accept it, and the admin scope check would never run: an auth bypass by changing the method. Path first, method second. A second trap: the path we route on must be the path we forward. `/orders/../billing` would match `/orders/{id}` while a backend resolves it to `/billing`. So the gateway cleans the path once (RFC 3986 section 5.2.4 dot-segment removal, merged slashes), refuses `..` above the root and percent-encoded dots or slashes with 400, and routes and forwards the same clean path.

```java
public interface Upstream { GatewayResponse handle(GatewayRequest request, Duration timeout); }
public interface AuthHook { AuthDecision check(GatewayRequest request, Route route, Principal current); }
```

The auth chain runs hooks in order and the first rejection wins. `ApiKeyAuthHook` reads `X-API-Key` (missing or wrong: the same 401, so nothing leaks). `ScopeAuthHook` checks the route's scope (missing: 403). After auth the gateway strips the secret key and any client-claimed `X-Client-Id`, and sets `X-Client-Id` itself, so a backend can trust it.

**Code delta:** `Route`, `RouteTable`, `PathNormalizer`, the auth classes, `Upstream`, `Gateway.handle`. **Proof:** `RouteTableTest` and `Round1RoutingAuthTest`; a rejected request never reaches the upstream (the fake counts calls).

### Round 2 (15 to 30 min): per-client rate limiting (reuse Topic 21)

**Interviewer says:** "One customer is hammering us. Protect the others, and tell well-behaved clients when to come back."

**A strong candidate** does NOT write a limiter: "Topic 21 gives me `KeyedRateLimiter`. I choose the key and the position." The key is `clientId|routeName`: per client so one cannot starve another, per route so a busy route does not lock out the client's other routes. Ids and route names may not contain `|`, so two pairs can never share a key.

Timeline with a limit of 3 per 10 seconds (token bucket, virtual time):

| Time | Request | Tokens before | Result |
|---|---|---|---|
| 0 s | GET /orders/1 (three times) | 3, 2, 1 | 200, 200, 200 |
| 0 s | GET /orders/1 | 0 | 429, `Retry-After: 4` (one token takes 3.33 s, rounded UP) |
| 4 s | GET /orders/1 | 1 | 200 |

`Retry-After` is whole seconds (RFC 9110 section 10.2.3). Rounding down would send the client back before its token exists. The limiter goes after auth (the key needs the identified client, and 401s must not spend tokens) and before the upstream (a rejection must be cheap).

**Code delta:** `Ticker` (one clock adapted into both Topic 21's and Topic 27's own `Ticker` types), the limiter step, `GatewayConfig`. **Proof:** `Round2RateLimitTest`: 429 and `Retry-After`, honest wait, clients and routes independent, auth and routing rejections spend no token.

### Round 3 (30 to 45 min): resilience (reuse Topic 27)

**Interviewer says:** "Billing is down. What happens to the gateway, and what happens to a POST /orders that times out?"

**A strong candidate** names four tools and where each sits:

- **Breaker per upstream** (`BreakerRegistry`). Billing opening must not stop orders. Open means 503 at once with a `Retry-After`. After the wait one probe goes through: success closes it, failure reopens it.
- **What counts as a failure:** 5xx answers, timeouts and unreachable. A 4xx is the caller's fault, but the breaker records it as a success, so a flood of 404s dilutes the failure rate. It never adds to it.
- **Retry only if repeating is safe:** GET, HEAD, OPTIONS, PUT, DELETE; POST and PATCH only with a non-blank `Idempotency-Key`. Only 502/503/504, timeouts and unreachable are retried. A plain 500 is usually a bug that fails again (Envoy's `gateway-error` retry condition is the same 502/503/504 set). A **retry budget** (Topic 27) caps retries to a share of all forwarded traffic, so retries cannot multiply load on a struggling backend. A refused retry still spent a budget token, because Topic 27's `Retry` takes the token before it sleeps.
- **Deadline:** one time budget for the whole request. Each call gets `min(time left, per-try timeout)`. A retry whose wait would pass the deadline is refused, and a late-ending sleep cannot start an attempt past the deadline. When a retry is refused, the client gets the backend's last answer (for example its 503), and 504 only when the backend never answered.

Timeline with deadline 1 s and calls that time out after 400 ms (virtual time):

| Time | What happens | Result |
|---|---|---|
| 0 ms | attempt 1, timeout given 1000 ms | times out at 400 ms |
| 400 ms | wait of 50 to 100 ms fits | attempt 2, timeout given about 500 ms |
| about 900 ms | attempt 2 times out; the next wait would pass 1000 ms | stop, answer 504 |

**Code delta:** breaker registry in `Gateway.forward`, retry loop, `retryAllowed`, `worthRetrying`, `UpstreamFailureException` (carries a 5xx through the breaker so it counts, then returns the backend's own answer). **Proof:** `Round3ResilienceTest`.

## 3. The order of the steps, and why

```
clean path -> route lookup -> auth -> rate limit -> [retry loop -> breaker -> timeout -> upstream]
 400 / 413     404 / 405    401 / 403      429          503 / 502 / 504 / backend's own answer
```

The rule: **the cheapest and most certain rejection first; a request rejected at a step never reaches a later step, so it costs nothing there.**

- **Routing before auth:** auth needs the route (the required scope).
- **Auth before limit:** the key is the identified client, and unauthenticated floods must not use up a real client's tokens. The cost is that 401 and 403 are not rate-limited, so API-key guessing is unbounded. A per-IP limiter in front of auth fixes that (design delta, not coded; see the Gaps). Meanwhile bodies are capped (413) before anything runs.
- **Limit before breaker:** a 429 is the gateway protecting itself, not a sign the backend is sick. If limiter rejections counted as breaker failures, one noisy client could open the breaker for everybody. Test: `limiterRejectionsDoNotCountAsBreakerFailures` (20 requests: 3 allowed, 17 rejected, 0 breaker failures).
- **Retry outside the breaker:** every attempt asks the breaker for permission, so when the breaker opens during a retry loop the next attempt is refused at once. Test: `breakerOpeningInsideTheLoopStopsTheLoop`. This is our reasoning; Resilience4j's documented aspect order is the same: Retry wraps CircuitBreaker, which wraps RateLimiter.
- **Deadline starts when the request is admitted**, and every retry shares it.

Two orders that lose: limit before auth (bad requests use real clients' tokens), and breaker outside retry (a retry that hides a failure also hides it from the breaker).

## 4. The end-to-end scenario

`EndToEndTest` runs one client journey on virtual time (limit 3 per 10 s, breaker opens at 50% failures after 2 calls, billing switched to 500 and later healed):

| Step | Request | Status | Why |
|---|---|---|---|
| 1 | GET /orders/1 as alice | 200 | normal |
| 2 | GET /orders/1 with no key | 401 | auth |
| 3 | POST /orders as bob (read-only scope) | 403 | scope |
| 4 | two more alice GETs (200, 200), then a fourth | 429 | limit |
| 5 | billing fails twice (500, 500), then GET /billing | 503 | breaker open, upstream not called |
| 6 | billing healed, 30 s later, GET /billing | 200 | half-open probe succeeds, breaker closes |

It asserts the exact statuses `[200, 401, 403, 429, 503, 200]`, 3 orders calls, 3 billing calls (two failures and the probe, not the 503), and 0 orders-breaker failures.

## 5. Concurrency model and cost

- **Shared state:** the limiter's per-key state (Topic 21: one atomic update per key), each upstream's breaker (Topic 27: one lock each), each upstream's retry budget (one lock each, created through a `ConcurrentHashMap`).
- **Invariant:** the gateway keeps no per-request state in fields. The deadline, the last failure and the random source are locals of one call, so two requests cannot see each other.
- **Random:** one `SplittableRandom` shared by all threads is unsafe, so the gateway takes a `Supplier<RandomGenerator>` (production: `ThreadLocalRandom::current`).
- **Cost:** a route lookup is one scan, O(routes x depth). Hundreds of routes are fine; a segment trie would make it O(depth) (design delta, not coded). The rest of a request is O(1) map lookups. Threading primitives are in [Java-poc concurrency](../../internal-pocs/Java-poc/concurrency/).

## 6. Code and test map

| Piece | File | Test |
|---|---|---|
| Pipeline and order | [Gateway](src/main/java/com/lld/gateway/Gateway.java) | [EndToEndTest](src/test/java/com/lld/gateway/EndToEndTest.java) |
| Route match, 404/405 | [Route](src/main/java/com/lld/gateway/Route.java), [RouteTable](src/main/java/com/lld/gateway/RouteTable.java) | [RouteTableTest](src/test/java/com/lld/gateway/RouteTableTest.java) |
| Path cleaning | [PathNormalizer](src/main/java/com/lld/gateway/PathNormalizer.java) | [Round1RoutingAuthTest](src/test/java/com/lld/gateway/Round1RoutingAuthTest.java) |
| Auth chain | [AuthChain](src/main/java/com/lld/gateway/AuthChain.java), [ApiKeyAuthHook](src/main/java/com/lld/gateway/ApiKeyAuthHook.java), [ScopeAuthHook](src/main/java/com/lld/gateway/ScopeAuthHook.java) | Round1RoutingAuthTest |
| Limit step, clock adapters | Gateway, [Ticker](src/main/java/com/lld/gateway/Ticker.java) | [Round2RateLimitTest](src/test/java/com/lld/gateway/Round2RateLimitTest.java) |
| Breaker, retry, deadline | Gateway, [UpstreamFailureException](src/main/java/com/lld/gateway/UpstreamFailureException.java) | [Round3ResilienceTest](src/test/java/com/lld/gateway/Round3ResilienceTest.java) |
| REST adapter (`/gw/**`) | [GatewayController](../app/src/main/java/com/lld/app/gateway/GatewayController.java) | [GatewayControllerTest](../app/src/test/java/com/lld/app/gateway/GatewayControllerTest.java) |

## 7. The Gaps

### F1: Rate limit across several gateway nodes

Each node has its own in-memory limiter, so N nodes allow N times the limit. Fix: a shared store (Redis + Lua, see `../../pocs/Redis-poc/`) or split the limit by N. Also add a per-IP limiter in front of auth to bound key guessing.

### F2: Weighted and tiered limits

Free and paid plans differ. Delta: the limit comes from the `Principal` (plan), and a route costs several permits (`tryAcquire(key, 10)` for an export).

### F3: Hedged requests

Send a second try to another instance when the first is slow. Idempotent requests only, counted by the retry budget. Not coded: it needs several instances per upstream.

### Common mistakes

- Checking the method before the path depth (auth bypass), or routing on a path that is not the forwarded path.
- Counting 429 or 4xx as breaker failures.
- Retrying POST: a timed-out POST may have succeeded, and the retry creates a duplicate.
- Retry loops with no deadline, so total latency is attempts times timeout.
- Using the secret API key as the limiter key (it leaks into logs), or forwarding it to the backend.
- Rounding `Retry-After` down, or swapping 401 (who are you) with 403 (you may not).

### Trade-offs

Use a self-run proxy such as Envoy, or a managed gateway, when you only need standard behaviour. Build your own only when the policy is domain-specific. A gateway is a single point of failure and a latency tax, so keep business logic out. Note the difference in route matching: Envoy takes the first matching route in list order, nginx `location` takes the longest prefix. We use longest-prefix, so order in the table never changes the answer.

### Production delta

Metrics per step (rejects by reason, breaker state, retries), request ids, hot config reload, outlier detection per upstream instance, connection pools and a bulkhead per upstream (Topic 27 has `Bulkhead`; not wired here), graceful shutdown. Measured here: the core tests run in about 1.5 s total on virtual time (JDK 21.0.8, Maven, Apple silicon laptop).

## 8. Official References

- RFC 9110 HTTP Semantics: 401, 403, 404, 405 and `Allow`, 503, `Retry-After` (10.2.3), idempotent methods (9.2.2), HEAD (9.3.2), 401 challenge (15.5.2), hop-by-hop headers (7.6.1): https://www.rfc-editor.org/rfc/rfc9110
- RFC 6585 (429 Too Many Requests): https://www.rfc-editor.org/rfc/rfc6585
- RFC 3986 section 5.2.4, removing dot-segments: https://www.rfc-editor.org/rfc/rfc3986
- Envoy route matching (routes are tried in order; `prefix` is a plain string prefix, `path_separated_prefix` respects `/` boundaries): https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/route/v3/route_components.proto
- Envoy circuit breaking (connection and request limits, the bulkhead idea; failure ejection is outlier detection): https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/circuit_breaking and https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/outlier
- Envoy retry budgets (`circuit_breakers.thresholds.retry_budget`): https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/cluster/v3/circuit_breaker.proto
- Envoy router filter (`retry_on: gateway-error` is 502/503/504): https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/router_filter
- nginx `location` (longest prefix, then regular expressions in order): https://nginx.org/en/docs/http/ngx_http_core_module.html
- Resilience4j aspect order, Retry(CircuitBreaker(RateLimiter(...))): https://resilience4j.readme.io/docs/getting-started-3
- Google SRE Book, ch. 21 Handling Overload https://sre.google/sre-book/handling-overload/ and ch. 22 Addressing Cascading Failures (deadlines, retries) https://sre.google/sre-book/addressing-cascading-failures/

## 9. 45-Minute Timeline

| Minutes | Round | You do |
|---|---|---|
| 0 to 5 | 1 | Clarifying questions, v1, draw the pipeline, write the `Upstream` and `AuthHook` interfaces |
| 5 to 15 | 1 | Route table (path first, then method), path cleaning, auth chain with 401/403; checkpoint: Round 1 tests |
| 15 to 20 | 2 | "I reuse the Topic 21 limiter", choose key and position, explain `Retry-After` rounding |
| 20 to 30 | 2 | Code the step, show the 429 timeline; checkpoint: Round 2 tests, auth rejections spend no token |
| 30 to 38 | 3 | Breaker per upstream, what counts as a failure, retry rules, retry budget |
| 38 to 45 | 3 | Deadline and timeouts, justify the order, run the end-to-end test, name F1 to F3 |

## 10. Senior vs Staff Rubric

| Round | Senior | Staff |
|---|---|---|
| 1 | Working router, 401/403, 404 | Path first then method (no fall-through bypass), whole-segment prefixes, path cleaned once and forwarded as routed, 405 with `Allow`, credential stripped, startup check of upstream names |
| 2 | Calls a limiter and returns 429 | Reuses Topic 21, key = client + route (never the secret), after auth, honest `Retry-After`, names the multi-node gap and the unbounded-401 hole unprompted |
| 3 | Adds retry and a breaker | Defends the order, limiter rejections never count as breaker failures, idempotent-only retries, retry budget, deadline shrinks timeouts and returns the last real answer |
| All | Tests that pass | Virtual time, no sleeps, mutation-checked tests, one end-to-end journey with exact statuses |

## 11. Key Takeaways

- A gateway is mostly ordering. Cheap and certain rejections first; a rejected request must cost nothing later.
- Most specific path first, method second. Route on the same path you forward.
- Reuse the parts you own (Topics 21, 27) and spend the interview on the glue.
- A breaker counts the backend's failures only: not 429, not 4xx, not auth rejections.
- Retry only what is safe to repeat, cap retries with a budget, bound all of it with a deadline.

**Self-grade: STRONG.** Full spine, 3 rounds with checkpoints, worked tables before code, a mutation-checked test suite (table in `commands.md`). Remaining gaps: the limiter is per-node and 401s are not limited before auth (both described as F1, not coded), no bulkhead, and the upstreams are in-memory fakes so real HTTP timeouts are only a seam.

[← Topic 45: Content Popularity](../45-content-popularity/) · [Index](../README.md) · [Topic 47: Capstone, Ticketing Platform →](../47-capstone-ticketing-platform/)
