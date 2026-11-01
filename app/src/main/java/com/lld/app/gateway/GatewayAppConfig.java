package com.lld.app.gateway;

import com.lld.gateway.ApiKeyAuthHook;
import com.lld.gateway.AuthChain;
import com.lld.gateway.Gateway;
import com.lld.gateway.GatewayConfig;
import com.lld.gateway.Principal;
import com.lld.gateway.Route;
import com.lld.gateway.RouteTable;
import com.lld.gateway.ScopeAuthHook;
import com.lld.gateway.Ticker;
import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import com.lld.resilience.CircuitBreakerConfig;
import com.lld.resilience.Sleeper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Connects the framework-free gateway core to Spring. Bean names carry the feature name and the clock uses the gateway's own Ticker type, so no shared-type bean is added. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayAppConfig {

    /** Provides the real clock as a bean named gatewayTicker, so tests can replace it with virtual time. */
    @Bean
    Ticker gatewayTicker() {
        return Ticker.system();
    }

    /** Provides the sleeper the retry loop waits with, as a bean named gatewaySleeper so tests can replace it. */
    @Bean
    Sleeper gatewaySleeper() {
        return Sleeper.system();
    }

    /** Provides the jitter source as a bean named gatewayRandom. It hands out the calling thread's own generator, because one shared generator is not safe across request threads. */
    @Bean
    Supplier<RandomGenerator> gatewayRandom() {
        return ThreadLocalRandom::current;
    }

    /** Provides the demo backends as one bean, so tests can break them. */
    @Bean
    FakeBackends gatewayBackends() {
        return new FakeBackends();
    }

    /** Builds the demo gateway: three routes, three API keys and the limits from {@code lld.gateway.*}. */
    @Bean
    Gateway gateway(GatewayProperties p, Ticker gatewayTicker, FakeBackends gatewayBackends, Sleeper gatewaySleeper, Supplier<RandomGenerator> gatewayRandom) {
        List<Route> routes = List.of(
                new Route("order-get", Set.of("GET"), "/orders/{id}", "orders", "orders:read"),
                new Route("order-create", Set.of("POST"), "/orders", "orders", "orders:write"),
                new Route("billing", Set.of("GET"), "/billing", "billing", "billing:read"));
        Map<String, Principal> keys = Map.of(
                "key-alice", new Principal("alice", Set.of("orders:read", "orders:write", "billing:read")),
                "key-bob", new Principal("bob", Set.of("orders:read")));
        GatewayConfig config = new GatewayConfig(RateLimiterFactory.Algorithm.TOKEN_BUCKET, new RateLimit(p.permits(), p.window()),
                CircuitBreakerConfig.countBased(50, p.breakerMinCalls(), Math.max(10, p.breakerMinCalls()), p.breakerOpenWait(), 1),
                p.maxAttempts(), Duration.ofMillis(100), Duration.ofSeconds(1), 100, 10, p.deadline(), p.perTryTimeout(), p.maxBodyBytes());
        return new Gateway(new RouteTable(routes), new AuthChain(List.of(new ApiKeyAuthHook(keys), new ScopeAuthHook())),
                Map.of("orders", gatewayBackends.orders, "billing", gatewayBackends.billing), config, gatewayTicker, gatewaySleeper, gatewayRandom);
    }
}
