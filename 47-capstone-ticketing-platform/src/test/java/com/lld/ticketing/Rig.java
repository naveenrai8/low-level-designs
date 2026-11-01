package com.lld.ticketing;

import com.lld.booking.BookingConfig;
import com.lld.booking.SeatHoldBookingService;
import com.lld.ledger.Account;
import com.lld.ledger.AccountType;
import com.lld.ledger.Draft;
import com.lld.ledger.InMemoryLedger;
import com.lld.ledger.Posting;
import com.lld.notify.ChannelException;
import com.lld.notify.ChannelType;
import com.lld.notify.EmailChannel;
import com.lld.notify.InMemoryPreferenceStore;
import com.lld.notify.NotificationService;
import com.lld.notify.RetryPolicy;
import com.lld.notify.Template;
import com.lld.notify.Transport;
import com.lld.notify.UserPreferences;
import com.lld.ratelimiter.RateLimit;
import com.lld.ratelimiter.RateLimiterFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

/** Shared test setup: the three reused modules wired on one virtual clock, a recording email transport, two funded buyers, and a crash hook the test can change. */
final class Rig {

    static final Instant T0 = Instant.parse("2026-01-05T12:00:00Z");
    static final long PRICE = 5000;
    static final long WALLET = 20000;

    /** One movable clock that is both the wall clock and the rate limiter's ticker. */
    static final class Virtual extends Clock {
        final AtomicLong nanos = new AtomicLong();

        /** Returns the fake current time. */
        @Override
        public Instant instant() {
            return T0.plusNanos(nanos.get());
        }

        /** Returns UTC. */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** Zones are irrelevant, so the same clock comes back. */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** Moves time forward. */
        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    final Virtual clock = new Virtual();
    final InMemoryLedger ledger = new InMemoryLedger(clock);
    final SeatHoldBookingService bookings;
    final InMemoryPreferenceStore prefs = new InMemoryPreferenceStore();
    final NotificationService notifications;
    final CheckoutService checkout;
    final List<String> delivered = new CopyOnWriteArrayList<>();
    final List<String> deliveredKeys = new CopyOnWriteArrayList<>();
    volatile boolean transportDown;
    volatile CrashHook behaviour = CrashHook.NONE;
    private final AtomicInteger bookingSeq = new AtomicInteger();
    private final AtomicInteger holdSeq = new AtomicInteger();
    private final AtomicInteger noteSeq = new AtomicInteger();

    /** Builds everything: a show "s1" with seats A1, A2, A3, buyers "ann" and "bob" with 20000 in their wallets, and email addresses for both. */
    Rig() {
        bookings = new SeatHoldBookingService(clock, BookingConfig.defaults(), () -> "h" + holdSeq.incrementAndGet(), () -> "b" + bookingSeq.incrementAndGet());
        bookings.addShow("s1", Set.of("A1", "A2", "A3"));
        ledger.openAccount(Account.overdraft("bank", AccountType.ASSET, "USD"));
        ledger.openAccount(Account.of("venue", AccountType.LIABILITY, "USD"));
        for (String buyer : List.of("ann", "bob")) {
            ledger.openAccount(Account.of(CheckoutService.walletOf(buyer), AccountType.LIABILITY, "USD"));
            ledger.post(Draft.of("top up", Posting.debit("bank", WALLET), Posting.credit(CheckoutService.walletOf(buyer), WALLET)));
            prefs.put(buyer, new UserPreferences(ZoneOffset.UTC, Map.of(ChannelType.EMAIL, buyer + "@x.com"), Set.of(ChannelType.EMAIL), Optional.empty()));
        }
        Transport transport = (address, body, key) -> {
            if (transportDown) {
                throw ChannelException.transientFailure("provider down");
            }
            delivered.add(address + "|" + body);
            deliveredKeys.add(key);
        };
        notifications = new NotificationService(clock, prefs, Map.of(ChannelType.EMAIL, new EmailChannel(transport)),
                Map.of(CheckoutService.TEMPLATE, new Template("Booked {{booking}} seats {{seats}}")),
                RateLimiterFactory.create(RateLimiterFactory.Algorithm.TOKEN_BUCKET, RateLimit.perMinute(1000), () -> clock.nanos.get()),
                new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(8), new Random(1)), () -> "n" + noteSeq.incrementAndGet());
        checkout = new CheckoutService(clock, bookings, ledger, ledger, notifications, TicketingConfig.forVenue("venue"), (p, k) -> behaviour.at(p, k));
    }

    /** A request by the buyer for the given seats at the standard price. */
    static CheckoutRequest request(String buyer, String... seats) {
        return new CheckoutRequest(buyer, "s1", List.of(seats), PRICE);
    }

    /** Makes the hook throw a simulated crash the first time the given point is reached, then never again (so the retry passes). */
    void crashOnce(CrashHook.Point point) {
        AtomicInteger fired = new AtomicInteger();
        behaviour = (p, k) -> {
            if (p == point && fired.getAndIncrement() == 0) {
                throw new SimulatedCrash(p);
            }
        };
    }

    /** How many ledger entries paid money to the venue and were not reversals: the number of captures. */
    long captures() {
        return ledger.journal().stream().filter(e -> e.reversesSeq() == 0 && e.postings().stream().anyMatch(p -> p.accountId().equals("venue"))).count();
    }

    /** The wallet balance not currently reserved by a payment hold. */
    long available(String buyer) {
        return ledger.available(CheckoutService.walletOf(buyer));
    }

    /** The error a test throws from the hook to play "the process died here". */
    static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Names the point where the process "died". */
        SimulatedCrash(CrashHook.Point point) {
            super("crash at " + point);
        }
    }
}
