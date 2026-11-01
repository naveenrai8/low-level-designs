package com.lld.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Round 1: how the route table picks a route, and the 404 / 405 answers. */
class RouteTableTest {

    /** Builds a route to the upstream "u" from a comma-separated method list. */
    private static Route route(String name, String methods, String pattern) {
        return new Route(name, Set.of(methods.split(",")), pattern, "u", null);
    }

    /** Proves the deepest matching prefix wins even when a shorter prefix is listed first. */
    @Test
    void longestPrefixWinsOverFirstListed() {
        RouteTable t = new RouteTable(List.of(route("short", "GET", "/api"), route("long", "GET", "/api/orders")));
        assertEquals("long", t.lookup("GET", "/api/orders/7").route().name());
        assertEquals("short", t.lookup("GET", "/api/users").route().name());
    }

    /** Proves a fixed segment beats a placeholder of the same depth, so /users/me is not swallowed by /users/{id}. */
    @Test
    void literalSegmentBeatsPlaceholderOfSameDepth() {
        RouteTable t = new RouteTable(List.of(route("byId", "GET", "/users/{id}"), route("me", "GET", "/users/me")));
        assertEquals("me", t.lookup("GET", "/users/me").route().name());
        assertEquals("byId", t.lookup("GET", "/users/9").route().name());
    }

    /** Proves a prefix only matches whole segments, so /orders does not match /ordersX. */
    @Test
    void prefixMatchesWholeSegmentsOnly() {
        RouteTable t = new RouteTable(List.of(route("o", "GET", "/orders")));
        assertEquals(RouteTable.Result.NOT_FOUND, t.lookup("GET", "/ordersX").result());
        assertEquals(RouteTable.Result.FOUND, t.lookup("GET", "/orders/1/items").result());
    }

    /** Proves path parameters are captured by name. */
    @Test
    void capturesPathParameters() {
        RouteTable t = new RouteTable(List.of(route("i", "GET", "/orders/{id}/items/{item}")));
        assertEquals(java.util.Map.of("id", "42", "item", "7"), t.lookup("GET", "/orders/42/items/7").params());
    }

    /** Proves an unknown path is NOT_FOUND (404). */
    @Test
    void unknownPathIsNotFound() {
        RouteTable t = new RouteTable(List.of(route("o", "GET", "/orders")));
        assertEquals(RouteTable.Result.NOT_FOUND, t.lookup("GET", "/nope").result());
    }

    /** Proves a known path with a wrong method is METHOD_NOT_ALLOWED (405) and lists the methods that would work. */
    @Test
    void wrongMethodIsMethodNotAllowedWithAllowedList() {
        RouteTable t = new RouteTable(List.of(route("g", "GET", "/orders"), route("p", "POST", "/orders")));
        RouteTable.Lookup l = t.lookup("DELETE", "/orders");
        assertEquals(RouteTable.Result.METHOD_NOT_ALLOWED, l.result());
        assertEquals(Set.of("GET", "HEAD", "POST"), l.allowed());
    }

    /** Proves the method is chosen per route: the same path with another method reaches a different route. */
    @Test
    void sameDeepPathPicksRouteByMethod() {
        RouteTable t = new RouteTable(List.of(route("g", "GET", "/orders"), route("p", "POST", "/orders")));
        assertEquals("p", t.lookup("post", "/orders").route().name());
    }

    /** Proves a wrong method on the most specific path is 405 and never falls through to a shallower route. This is the admin-bypass trap: DELETE must not slip past /admin onto the catch-all. */
    @Test
    void wrongMethodOnDeepRouteIs405NotFallThrough() {
        RouteTable t = new RouteTable(List.of(route("admin", "GET", "/admin"), route("all", "GET,POST,DELETE", "/")));
        RouteTable.Lookup l = t.lookup("DELETE", "/admin/users/7");
        assertEquals(RouteTable.Result.METHOD_NOT_ALLOWED, l.result());
        assertEquals(Set.of("GET", "HEAD"), l.allowed());
    }

    /** Proves HEAD is accepted wherever GET is, and shows up in the Allow list. */
    @Test
    void headIsAllowedWhereGetIs() {
        RouteTable t = new RouteTable(List.of(route("g", "GET", "/x")));
        assertEquals("g", t.lookup("HEAD", "/x").route().name());
    }

    /** Proves two patterns with the same shape but different placeholder names are rejected, because list order would decide. */
    @Test
    void sameShapeDifferentParamNamesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(route("a", "GET", "/users/{id}"), route("b", "GET", "/users/{name}"))));
    }

    /** Proves that for /a/{x} versus /{x}/b the leftmost fixed segment decides, whichever order they are listed in. */
    @Test
    void leftmostLiteralDecidesBetweenMixedPatterns() {
        Route left = route("left", "GET", "/a/{x}");
        Route right = route("right", "GET", "/{x}/b");
        assertEquals("left", new RouteTable(List.of(left, right)).lookup("GET", "/a/b").route().name());
        assertEquals("left", new RouteTable(List.of(right, left)).lookup("GET", "/a/b").route().name());
    }

    /** Proves a route name with the key separator is refused, so two different (client, route) pairs can never share a limiter key. */
    @Test
    void routeNameWithPipeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> route("a|b", "GET", "/x"));
    }

    /** Proves two routes with the same pattern and method are refused at startup. */
    @Test
    void overlappingRoutesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteTable(List.of(route("a", "GET", "/x"), route("b", "GET,POST", "/x"))));
    }

    /** Proves doubled and trailing slashes do not change the match. */
    @Test
    void extraSlashesAreIgnored() {
        RouteTable t = new RouteTable(List.of(route("o", "GET", "/orders/{id}")));
        assertEquals("5", t.lookup("GET", "//orders//5/").params().get("id"));
    }
}
