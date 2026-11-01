package com.lld.app.notify;

import com.lld.notify.Channel;
import com.lld.notify.ChannelType;
import com.lld.notify.EmailChannel;
import com.lld.notify.InMemoryPreferenceStore;
import com.lld.notify.NotificationService;
import com.lld.notify.PushChannel;
import com.lld.notify.QuietHours;
import com.lld.notify.RetryPolicy;
import com.lld.notify.SmsChannel;
import com.lld.notify.Template;
import com.lld.notify.Transport;
import com.lld.notify.UserPreferences;
import com.lld.ratelimiter.KeyedRateLimiter;
import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import com.lld.ratelimiter.Ticker;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Connects the framework-free notification core to Spring. The core never sees a Spring type. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotifyProperties.class)
class NotifyConfig {

    /** Provides the wall clock as a bean named notifyClock, so tests can replace it. The name avoids clashing with the other Clock beans. */
    @Bean
    Clock notifyClock() {
        return Clock.systemUTC();
    }

    /** The user preferences, with one demo user (alice, New York, quiet 22:00 to 07:00) so the API can be tried with curl. */
    @Bean
    InMemoryPreferenceStore notifyPreferences() {
        InMemoryPreferenceStore store = new InMemoryPreferenceStore();
        store.put("alice", new UserPreferences(ZoneId.of("America/New_York"),
                Map.of(ChannelType.EMAIL, "alice@example.com", ChannelType.SMS, "+15550100", ChannelType.PUSH, "device-1"),
                Set.of(ChannelType.EMAIL, ChannelType.SMS, ChannelType.PUSH),
                Optional.of(new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0)))));
        return store;
    }

    /** A transport that delivers nothing; a real one would call an email or SMS provider. Swap this bean to go live. */
    @Bean
    Transport notifyTransport() {
        return (address, body, key) -> { };
    }

    /** Builds the service from the {@code lld.notify.*} settings. Ids are random UUIDs, supplied here and not inside the core. */
    @Bean
    NotificationService notificationService(NotifyProperties props, Clock notifyClock, InMemoryPreferenceStore notifyPreferences, Transport notifyTransport) {
        Map<ChannelType, Channel> channels = Map.of(ChannelType.EMAIL, new EmailChannel(notifyTransport), ChannelType.SMS, new SmsChannel(notifyTransport), ChannelType.PUSH, new PushChannel(notifyTransport));
        Map<String, Template> templates = Map.of("welcome", new Template("Welcome {{name}}!"), "otp", new Template("Your code is {{code}}"));
        // This is the real runtime wiring: the limiter's ticker is derived from the wall-clock Clock bean, so freezing one clock freezes both in tests (Topic 21's app package already owns the one Ticker bean, so a second Ticker bean would be ambiguous). The catch: wall-clock time is not monotonic. If the clock steps back, a limited user waits longer; if it steps forward, at most one extra bucket gets through. That is acceptable for a demo; if you need it monotonic, use Ticker.system() and keep a separate wall Clock for quiet hours.
        Ticker ticker = () -> ChronoUnit.NANOS.between(Instant.EPOCH, notifyClock.instant());
        KeyedRateLimiter<?> limiter = RateLimiterFactory.create(RateLimiterFactory.Algorithm.TOKEN_BUCKET, RateLimit.perMinute(props.rateLimitPerMinute()), ticker);
        RetryPolicy retry = new RetryPolicy(props.maxAttempts(), props.backoffBase(), props.backoffCap(), new Random(props.jitterSeed()));
        return new NotificationService(notifyClock, notifyPreferences, channels, templates, limiter, retry, () -> "ntf-" + UUID.randomUUID());
    }

    /** Creates the timer job that sends what is due. */
    @Bean
    NotifyWorker notifyWorker(NotificationService service) {
        return new NotifyWorker(service);
    }

    /** Runs {@link NotificationService#runDue()} on a timer. */
    static final class NotifyWorker {
        private final NotificationService service;

        /** Creates a worker for the service. */
        NotifyWorker(NotificationService service) {
            this.service = service;
        }

        /** Sends everything due, every {@code lld.notify.run-interval} (set in application.yml). */
        @Scheduled(fixedDelayString = "${lld.notify.run-interval}")
        void run() {
            service.runDue();
        }
    }
}
