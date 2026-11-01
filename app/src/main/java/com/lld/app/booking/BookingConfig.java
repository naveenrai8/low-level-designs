package com.lld.app.booking;

import com.lld.booking.BookingService;
import com.lld.booking.SeatHoldBookingService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Connects the framework-free booking core to Spring. The core never sees a Spring type. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingProperties.class)
class BookingConfig {

    /** Provides the wall clock as a bean named bookingClock, so tests can replace it with a controllable one. The name avoids clashing with any other Clock bean. */
    @Bean
    Clock bookingClock() {
        return Clock.systemUTC();
    }

    /** Builds the booking service from the {@code lld.booking.*} settings and registers a show named demo. Ids are random UUIDs, supplied here and not inside the core. */
    @Bean
    BookingService bookingService(BookingProperties props, Clock bookingClock) {
        com.lld.booking.BookingConfig config = new com.lld.booking.BookingConfig(props.holdTtl(), props.idempotencyTtl(), props.expiredHoldRetention());
        SeatHoldBookingService service = new SeatHoldBookingService(bookingClock, config, () -> "hold-" + UUID.randomUUID(), () -> "booking-" + UUID.randomUUID());
        // A demo show so the API can be tried with curl; a real service would load shows from its catalogue.
        service.addShow("demo", new java.util.TreeSet<>(java.util.List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10")));
        return service;
    }

    /** Creates the timer job that sweeps memory. It never changes an answer, it only forgets what is already dead. */
    @Bean
    BookingSweeper bookingSweeper(BookingService service) {
        return new BookingSweeper(service);
    }

    /** Runs {@link BookingService#sweep()} on a timer. */
    static final class BookingSweeper {
        private final BookingService service;

        /** Creates a sweeper for the service. */
        BookingSweeper(BookingService service) {
            this.service = service;
        }

        /** Sweeps every {@code lld.booking.sweep-interval} (set in application.yml). */
        @Scheduled(fixedDelayString = "${lld.booking.sweep-interval}")
        void sweep() {
            service.sweep();
        }
    }
}
