package com.lld.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Round 1 proof: the pipeline routes, authenticates, and never touches an upstream for a rejected request. */
class Round1RoutingAuthTest {

    /** Proves a valid key with the right scope reaches the upstream and gets its answer. */
    @Test
    void validRequestReachesUpstream() {
        Fixture f = new Fixture();
        GatewayResponse r = f.gateway.handle(Fixture.get("/orders/42", "key-alice"));
        assertEquals(200, r.status());
        assertEquals(1, f.orders.calls());
        assertEquals("42", f.orders.seen().get(0).pathParams().get("id"));
    }

    /** Proves a missing key is 401 with WWW-Authenticate and the upstream is not called. */
    @Test
    void missingKeyIs401() {
        Fixture f = new Fixture();
        GatewayResponse r = f.gateway.handle(GatewayRequest.of("GET", "/orders/1"));
        assertEquals(401, r.status());
        assertEquals("ApiKey", r.headers().get("WWW-Authenticate"));
        assertEquals(0, f.orders.calls());
    }

    /** Proves a wrong key gives the same 401 as a missing key. */
    @Test
    void unknownKeyIs401() {
        Fixture f = new Fixture();
        assertEquals(401, f.gateway.handle(Fixture.get("/orders/1", "nope")).status());
    }

    /** Proves a known caller without the scope gets 403, not 401. */
    @Test
    void missingScopeIs403() {
        Fixture f = new Fixture();
        assertEquals(403, f.gateway.handle(Fixture.post("/orders", "key-bob")).status());
        assertEquals(0, f.orders.calls());
    }

    /** Proves a route with no required scope needs a valid key but no scope. */
    @Test
    void routeWithoutScopeNeedsOnlyAKey() {
        Fixture f = new Fixture();
        assertEquals(200, f.gateway.handle(Fixture.get("/health", "key-erin")).status());
    }

    /** Proves an unknown path is 404 and a wrong method is 405 with an Allow header. */
    @Test
    void notFoundAndMethodNotAllowed() {
        Fixture f = new Fixture();
        assertEquals(404, f.gateway.handle(Fixture.get("/nope", "key-alice")).status());
        GatewayResponse r = f.gateway.handle(Fixture.post("/health", "key-alice"));
        assertEquals(405, r.status());
        assertEquals("GET, HEAD", r.headers().get("Allow"));
    }

    /** Proves the auth chain stops at the first rejection, so later hooks never run. */
    @Test
    void chainStopsAtFirstRejection() {
        int[] second = {0};
        AuthChain chain = new AuthChain(List.of(
                (req, route, who) -> AuthDecision.reject(GatewayResponse.of(401, "x")),
                (req, route, who) -> {
                    second[0]++;
                    return AuthDecision.pass(null);
                }));
        assertEquals(401, chain.run(GatewayRequest.of("GET", "/"), new Route("r", Set.of("GET"), "/", "u", null)).rejection().status());
        assertEquals(0, second[0]);
    }

    /** Proves a scope check with nobody identified answers 401, the safe answer for a misordered chain. */
    @Test
    void scopeHookWithoutCallerIs401() {
        Route needs = new Route("r", Set.of("GET"), "/", "u", "s");
        assertEquals(401, new ScopeAuthHook().check(GatewayRequest.of("GET", "/"), needs, null).rejection().status());
    }

    /** Proves a route that names a missing upstream fails at startup, not at request time. */
    @Test
    void unknownUpstreamFailsAtStartup() {
        Fixture f = new Fixture();
        RouteTable t = new RouteTable(List.of(new Route("r", Set.of("GET"), "/x", "ghost", null)));
        assertThrows(IllegalArgumentException.class, () -> new Gateway(t, new AuthChain(List.of()), Map.of(), Fixture.config(1, 0, Duration.ofSeconds(1)), f.clock.ticker(), f.clock.sleeper(), () -> new java.util.SplittableRandom(1)));
    }

    /** Builds a gateway where /admin needs the admin scope and a catch-all route at / allows GET, POST and DELETE with no scope. This is the trap layout for the method fall-through bug. */
    private static Fixture adminTrap() {
        List<Route> routes = List.of(
                new Route("admin", Set.of("GET"), "/admin", "orders", "admin"),
                new Route("catch-all", Set.of("GET", "POST", "DELETE"), "/", "orders", null));
        return new Fixture(Fixture.config(1, 0, Duration.ofSeconds(5)), routes);
    }

    /** Proves the admin-bypass trap: a DELETE on /admin/users/7 is 405 and the catch-all upstream never sees it, so the admin scope cannot be skipped by changing the method. */
    @Test
    void wrongMethodOnProtectedPathCannotBypassItsScope() {
        Fixture f = adminTrap();
        GatewayResponse r = f.gateway.handle(GatewayRequest.of("DELETE", "/admin/users/7").withHeader("X-API-Key", "key-bob"));
        assertEquals(405, r.status());
        assertEquals("GET, HEAD", r.headers().get("Allow"));
        assertEquals(0, f.orders.calls());
    }

    /** Proves the path that is routed is the path that is forwarded: dot-segments are removed once, so /orders/../billing is routed (and authorised) as /billing. */
    @Test
    void dotSegmentsAreResolvedBeforeRouting() {
        Fixture f = new Fixture();
        assertEquals(403, f.gateway.handle(Fixture.get("/orders/../billing", "key-alice")).status());
        assertEquals(200, f.gateway.handle(Fixture.get("/orders/../billing", "key-erin")).status());
        assertEquals("/billing", f.billing.seen().get(0).path());
    }

    /** Proves repeated slashes are merged and the clean path is what the upstream sees. */
    @Test
    void repeatedSlashesAreMergedAndForwardedClean() {
        Fixture f = new Fixture();
        f.gateway.handle(Fixture.get("//orders//5/", "key-alice"));
        assertEquals("/orders/5", f.orders.seen().get(0).path());
    }

    /** Proves a path that climbs above the root, or hides a dot or slash in percent-encoding (any case), is refused with 400 and never routed. */
    @Test
    void trickyPathsAreRejectedWith400() {
        Fixture f = new Fixture();
        for (String p : new String[] {"/../x", "/%2e%2e/x", "/orders/%2E%2e/billing", "/orders%2F5", "/orders\\5"}) {
            assertEquals(400, f.gateway.handle(Fixture.get(p, "key-alice")).status(), p);
        }
        assertEquals(0, f.orders.calls());
    }

    /** Proves a body over the size cap is 413 before routing or auth runs. */
    @Test
    void oversizedBodyIs413() {
        Fixture f = new Fixture();
        assertEquals(413, f.gateway.handle(Fixture.post("/orders", "key-alice").withBody("x".repeat(1025))).status());
        assertEquals(200, f.gateway.handle(Fixture.post("/orders", "key-alice").withBody("x".repeat(1024))).status());
    }

    /** Proves the secret API key and any client-claimed identity header never reach the upstream, and the gateway sets X-Client-Id from the real caller. */
    @Test
    void credentialsAreStrippedAndIdentityIsSetByTheGateway() {
        Fixture f = new Fixture();
        f.gateway.handle(Fixture.get("/orders/1", "key-alice").withHeader("X-Client-Id", "admin").withHeader("X-Other", "kept"));
        GatewayRequest seen = f.orders.seen().get(0);
        assertEquals(null, seen.header("x-api-key"));
        assertEquals("alice", seen.header("x-client-id"));
        assertEquals("kept", seen.header("x-other"));
    }

    /** Proves the query string travels to the upstream untouched. */
    @Test
    void queryStringIsForwarded() {
        Fixture f = new Fixture();
        GatewayRequest r = new GatewayRequest("GET", "/orders", Map.of("X-API-Key", "key-alice"), "", Map.of(), "page=2&sort=asc");
        f.gateway.handle(r);
        assertEquals("page=2&sort=asc", f.orders.seen().get(0).query());
    }

    /** Proves a 401 from the scope hook carries a WWW-Authenticate challenge, as RFC 9110 requires. */
    @Test
    void scopeHook401CarriesAChallenge() {
        Route needs = new Route("r", Set.of("GET"), "/", "u", "s");
        assertEquals("ApiKey", new ScopeAuthHook().check(GatewayRequest.of("GET", "/"), needs, null).rejection().headers().get("WWW-Authenticate"));
    }

    /** Proves a later hook that passes without naming anyone does not erase the caller an earlier hook identified. */
    @Test
    void laterAnonymousPassKeepsTheIdentifiedCaller() {
        AuthChain chain = new AuthChain(List.of(new ApiKeyAuthHook(Fixture.KEYS), (req, route, who) -> AuthDecision.pass(null)));
        Route r = new Route("r", Set.of("GET"), "/", "u", null);
        assertEquals("alice", chain.run(GatewayRequest.of("GET", "/").withHeader("X-API-Key", "key-alice"), r).principal().id());
    }

    /** Proves a chain that never identifies anyone ends in 401 and never reaches the limiter or the upstream. */
    @Test
    void unidentifiedCallerIsRejectedBeforeTheLimiter() {
        Fixture f = new Fixture();
        RouteTable t = new RouteTable(Fixture.STANDARD_ROUTES);
        Gateway g = new Gateway(t, new AuthChain(List.of()), Map.of("orders", f.orders, "billing", f.billing), Fixture.config(1, 0, Duration.ofSeconds(5)), f.clock.ticker(), f.clock.sleeper(), () -> new java.util.SplittableRandom(1));
        assertEquals(401, g.handle(Fixture.get("/health", "x")).status());
        assertEquals(0, f.orders.calls());
    }

    /** Proves a client id with the key separator is refused, so two different clients can never share a limiter key. */
    @Test
    void clientIdWithPipeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Principal("a|b", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Principal(" ", Set.of()));
    }

    /** Proves HEAD on a GET route reaches the upstream. */
    @Test
    void headIsServedByGetRoutes() {
        Fixture f = new Fixture();
        assertEquals(200, f.gateway.handle(GatewayRequest.of("HEAD", "/health").withHeader("X-API-Key", "key-alice")).status());
    }

    /** Proves a 405 lists every method of the matching path in the Allow header, in a fixed order, with HEAD added for GET. */
    @Test
    void allowHeaderListsEveryMethodOfThePath() {
        Fixture f = new Fixture();
        GatewayResponse r = f.gateway.handle(GatewayRequest.of("DELETE", "/billing").withHeader("X-API-Key", "key-erin"));
        assertEquals(405, r.status());
        assertEquals("GET, HEAD, POST", r.headers().get("Allow"));
    }
}
