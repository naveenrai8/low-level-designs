package com.lld.notify;

import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Shared test setup: a service wired with fakes, one movable virtual clock for both wall time and the rate limiter, and a recording transport. */
final class Fixture {

    /** One movable clock that is both the wall Clock and the limiter's Ticker, so a single advance moves both. */
    static final class Virtual extends Clock {
        final AtomicLong nanos = new AtomicLong();
        final Instant origin;

        /** Starts at the given instant. */
        Virtual(Instant origin) {
            this.origin = origin;
        }

        /** Returns the fake time. */
        @Override
        public Instant instant() {
            return origin.plusNanos(nanos.get());
        }

        /** Returns UTC. */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** Zones do not matter here. */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** Moves time forward. */
        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }

        /** Jumps to an absolute instant (must not be before the start). */
        void set(Instant t) {
            nanos.set(Duration.between(origin, t).toNanos());
        }
    }

    final Virtual clock;
    final InMemoryPreferenceStore prefs = new InMemoryPreferenceStore();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> sentKeys = new CopyOnWriteArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    /** What the transport does on each call; tests replace it to make it fail. */
    volatile Consumer<Integer> behaviour = n -> { };
    /** What the transport does for a given address; lets a test break one user's channel only. */
    volatile Consumer<String> byAddress = a -> { };
    final NotificationService service;
    final AtomicInteger idCounter = new AtomicInteger();

    /** Builds a service with a limit of {@code limitPerMinute} per user per minute and the given retry policy. */
    Fixture(Instant start, int limitPerMinute, int maxAttempts) {
        clock = new Virtual(start);
        Transport t = (address, body, key) -> {
            int n = calls.incrementAndGet();
            behaviour.accept(n);
            byAddress.accept(address);
            sent.add(address + "|" + body);
            sentKeys.add(key);
        };
        KeyedRateLimiter<?> limiter = RateLimiterFactory.create(RateLimiterFactory.Algorithm.TOKEN_BUCKET, RateLimit.perMinute(limitPerMinute), () -> clock.nanos.get());
        RetryPolicy retry = new RetryPolicy(maxAttempts, Duration.ofSeconds(1), Duration.ofSeconds(8), new Random(42));
        service = new NotificationService(clock, prefs,
                Map.of(ChannelType.EMAIL, new EmailChannel(t), ChannelType.SMS, new SmsChannel(t), ChannelType.PUSH, new PushChannel(t)),
                Map.of("hello", new Template("Hi {{name}}, code {{code}}"), "welcome", new Template("Welcome {{name}}!")),
                limiter, retry, () -> "n" + idCounter.incrementAndGet());
        addUser("u1", ZoneOffset.UTC, Optional.empty());
    }

    /** Registers a user with all three channels on. */
    void addUser(String id, ZoneId zone, Optional<QuietHours> quiet) {
        prefs.put(id, new UserPreferences(zone, Map.of(ChannelType.EMAIL, "u@x.com", ChannelType.SMS, "+100", ChannelType.PUSH, "tok"),
                Set.of(ChannelType.EMAIL, ChannelType.SMS, ChannelType.PUSH), quiet));
    }

    /** A request to u1 with a normal hello template. */
    static NotificationRequest hello(String user, ChannelType ch) {
        return new NotificationRequest(user, ch, "hello", Map.of("name", "Ann", "code", "42"), Priority.NORMAL);
    }

    /** Quiet hours 22:00 to 07:00. */
    static QuietHours night() {
        return new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));
    }
}
