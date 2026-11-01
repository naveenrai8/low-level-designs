package com.lld.notify;

import com.lld.ratelimiter.Decision;
import com.lld.ratelimiter.KeyedRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Accepts notification requests and delivers them, respecting preferences, quiet hours, the per-user rate limit, retries and idempotency.
 *
 * <p>Two steps, on purpose:
 * <ol>
 *   <li>{@link #submit}: validate, render, record the idempotency key and the notification, return. Nothing is sent here, so the caller gets a fast answer (HTTP 202).</li>
 *   <li>{@link #runDue}: a worker (a timer in production, the test in tests) sends everything whose time has come.</li>
 * </ol>
 *
 * <h2>Rules, in the order runDue applies them to one message</h2>
 * <ol>
 *   <li><b>Opted out</b> of the channel (checked now, not at submit): status SUPPRESSED. Nothing is sent, and urgent messages do not override an opt-out.</li>
 *   <li><b>Quiet hours</b>: a NORMAL message is deferred to the end of quiet hours, not dropped. An URGENT message ignores quiet hours.</li>
 *   <li><b>Rate limit</b> (Topic 21, key = user): if the user is over the limit, the message is deferred by the limiter's retry-after, not dropped. Urgent messages are limited too: the limit protects the user from a buggy caller. A deferral costs no attempt.</li>
 *   <li><b>Send</b>: success gives SENT. A permanent failure goes to the DLQ at once. A transient failure waits (backoff + jitter) and tries again, until the attempt limit, then the DLQ.</li>
 * </ol>
 *
 * <h2>Delivery guarantee: at-least-once</h2>
 * <p>The idempotency record and the PENDING notification are stored <b>before</b> anything is sent. The SENT status is stored <b>after</b>. If the process crashes between the send and that second write, the message is still PENDING after restart and is sent again: the user may get it twice, never zero times. To make the second send harmless, the notification id is passed to the provider as its idempotency key. The opposite choice (mark "sending" first and never resend it) would be at-most-once: no duplicates, but a crash loses the message.
 *
 * <h2>Concurrency</h2>
 * <p>Submit is lock-free except for {@link ConcurrentHashMap#computeIfAbsent}, which runs the "create it" code at most once per key even if 32 threads race, so duplicates produce one notification. {@link #runDue} is {@code synchronized}: one sender at a time. That is simple and keeps the seeded jitter and the rate limiter in a fixed order. Production would shard by user.
 */
public final class NotificationService {

    /** What we remember per idempotency key: the request (to spot a different payload) and the id we gave out. */
    private record IdempotencyRecord(NotificationRequest request, String id) {
    }

    private final Clock clock;
    private final PreferenceStore preferences;
    private final Map<ChannelType, Channel> channels;
    private final Map<String, Template> templates;
    private final KeyedRateLimiter<?> limiter;
    private final RetryPolicy retry;
    private final Supplier<String> ids;

    private final ConcurrentHashMap<String, IdempotencyRecord> keys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Notification> notifications = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Wires the service. Every outside thing (time, ids, users, channels, limits, jitter) is passed in, so tests control all of it.
     *
     * @param clock wall time, for quiet hours and "send at" times
     * @param limiter the per-user limiter from Topic 21; it was built with its own Ticker, which tests move together with the clock
     * @param ids makes unique notification ids
     */
    public NotificationService(Clock clock, PreferenceStore preferences, Map<ChannelType, Channel> channels, Map<String, Template> templates,
                               KeyedRateLimiter<?> limiter, RetryPolicy retry, Supplier<String> ids) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.channels = Map.copyOf(channels);
        this.templates = Map.copyOf(templates);
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /**
     * Accepts a request exactly once per idempotency key (see Topic 11 for the pattern).
     *
     * <p>Same key + equal request: returns the original id with replay=true and creates nothing. Same key + different request: throws. The request is checked and rendered first, so an invalid request never claims a key.
     *
     * @throws InvalidRequestException for a blank key, unknown user/template, a channel the user has no address for or the service lacks, or a missing template variable
     * @throws IdempotencyConflictException if the key was used with a different request
     */
    public SubmitResult submit(String idempotencyKey, NotificationRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidRequestException("idempotency key is required");
        }
        IdempotencyRecord known = keys.get(idempotencyKey);
        if (known != null) {
            // A repeat is answered from the record, before rendering, so a replay still works if the user or template changed since.
            if (!known.request().equals(request)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return new SubmitResult(known.id(), true);
        }
        String body = render(request);
        boolean[] created = {false};
        IdempotencyRecord rec = keys.computeIfAbsent(idempotencyKey, k -> {
            // Runs at most once per key, even under a race. The key and the notification are stored before any send.
            String id = ids.get();
            notifications.put(id, new Notification(id, sequence.incrementAndGet(), request.userId(), request.channel(), body, request.priority(), Status.PENDING, 0, "", clock.instant()));
            created[0] = true;
            return new IdempotencyRecord(request, id);
        });
        if (!created[0] && !rec.request().equals(request)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return new SubmitResult(rec.id(), !created[0]);
    }

    /** Checks the request against users, templates and channels, and returns the finished body (escaped for the channel, then cut to fit). */
    private String render(NotificationRequest r) {
        UserPreferences prefs = preferences.find(r.userId()).orElseThrow(() -> new InvalidRequestException("unknown user: " + r.userId()));
        Channel channel = channels.get(r.channel());
        if (channel == null) {
            throw new InvalidRequestException("channel not available: " + r.channel());
        }
        if (!prefs.addresses().containsKey(r.channel())) {
            throw new InvalidRequestException("user has no address for " + r.channel());
        }
        Template t = templates.get(r.template());
        if (t == null) {
            throw new InvalidRequestException("unknown template: " + r.template());
        }
        try {
            return channel.fit(t.render(r.variables(), channel::escape));
        } catch (TemplateException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    /** Returns the current snapshot of a notification, or empty if the id is unknown. */
    public Optional<Notification> find(String id) {
        return Optional.ofNullable(notifications.get(id));
    }

    /** Returns every notification in the dead letter queue, in the order they were submitted. */
    public List<DeadLetter> deadLetters() {
        List<Notification> dead = new ArrayList<>();
        for (Notification n : notifications.values()) {
            if (n.status() == Status.DEAD) {
                dead.add(n);
            }
        }
        dead.sort(Comparator.comparingLong(Notification::seq));
        List<DeadLetter> out = new ArrayList<>();
        for (Notification n : dead) {
            out.add(new DeadLetter(n.id(), n.reason(), n.attempts()));
        }
        
        return out;
    }

    /**
     * Handles every PENDING notification whose time has come, once each, in time order.
     *
     * <p>A notification that is deferred or fails transiently is rescheduled for later and is not handled again in the same call, because its new time is in the future.
     *
     * @return how many notifications were looked at (sent, deferred, retried, suppressed or killed)
     */
    public synchronized int runDue() {
        Instant now = clock.instant();
        List<Notification> due = new ArrayList<>();
        for (Notification n : notifications.values()) {
            if (n.status() == Status.PENDING && !n.nextAttemptAt().isAfter(now)) {
                due.add(n);
            }
        }
        due.sort(Comparator.comparing(Notification::nextAttemptAt).thenComparingLong(Notification::seq));
        for (Notification n : due) {
            notifications.put(n.id(), handle(n, now));
        }
        return due.size();
    }

    /** Applies the rules (opt-out, quiet hours, rate limit, send) to one due notification and returns its new snapshot. It never throws for a bad transport: any failure becomes a status. */
    private Notification handle(Notification n, Instant now) {
        Optional<UserPreferences> found = preferences.find(n.userId());
        if (found.isEmpty() || !found.get().allows(n.channel())) {
            return n.with(Status.SUPPRESSED, n.attempts(), "user opted out of " + n.channel(), n.nextAttemptAt());
        }
        UserPreferences prefs = found.get();
        if (n.priority() == Priority.NORMAL && prefs.quietHours().isPresent()) {
            Optional<Instant> end = prefs.quietHours().get().endAfter(now, prefs.zone());
            if (end.isPresent()) {
                return n.with(Status.PENDING, n.attempts(), "deferred: quiet hours until " + end.get(), end.get());
            }
        }
        Decision d = limiter.tryAcquire(n.userId());
        if (!d.allowed()) {
            return n.with(Status.PENDING, n.attempts(), "deferred: rate limited", now.plus(d.retryAfter()));
        }
        String address = prefs.addresses().get(n.channel());
        if (address == null) {
            // The user lost this address after we accepted the message. Nobody can fix that by retrying, but a person may want to know, so it is DEAD (visible in the DLQ), not SUPPRESSED (which means the user said no).
            return n.with(Status.DEAD, n.attempts(), "no address for " + n.channel(), n.nextAttemptAt());
        }
        int attempts = n.attempts() + 1;
        try {
            channels.get(n.channel()).send(address, n.body(), n.id());
            return n.with(Status.SENT, attempts, "", n.nextAttemptAt());
        } catch (ChannelException e) {
            if (!e.isTransient()) {
                return n.with(Status.DEAD, attempts, "permanent failure: " + e.getMessage(), n.nextAttemptAt());
            }
            return afterTransientFailure(n, attempts, now, e.getMessage());
        } catch (RuntimeException e) {
            // A transport bug or crash (not a ChannelException) must not escape runDue: the message would stay first in line forever and block everyone behind it. We count it as a transient failure, so it retries and then reaches the DLQ.
            return afterTransientFailure(n, attempts, now, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Decides between "wait and retry" and "give up" after a failure that may be temporary. */
    private Notification afterTransientFailure(Notification n, int attempts, Instant now, String message) {
        if (attempts >= retry.maxAttempts()) {
            return n.with(Status.DEAD, attempts, "retries exhausted: " + message, n.nextAttemptAt());
        }
        Duration wait = retry.delayAfter(attempts);
        return n.with(Status.PENDING, attempts, "retry " + attempts + " failed: " + message, now.plus(wait));
    }
}
