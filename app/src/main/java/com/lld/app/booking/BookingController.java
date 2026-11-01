package com.lld.app.booking;

import com.lld.booking.AlreadyConfirmedException;
import com.lld.booking.Booking;
import com.lld.booking.BookingService;
import com.lld.booking.ConfirmResult;
import com.lld.booking.Hold;
import com.lld.booking.HoldExpiredException;
import com.lld.booking.HoldNotFoundException;
import com.lld.booking.IdempotencyConflictException;
import com.lld.booking.SeatUnavailableException;
import com.lld.booking.ShowNotFoundException;
import com.lld.booking.UnknownSeatException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hold-then-confirm over HTTP. HTTP adds status codes and the {@code Idempotency-Key} header on top of the core.
 *
 * <p>Every domain exception is translated here, around exactly the core call. There is no global handler for IllegalArgumentException, because that would turn framework and JDK bugs into 400s.
 */
@RestController
@RequestMapping("/api/booking")
class BookingController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final BookingService service;

    /** Creates the controller around the service bean. */
    BookingController(BookingService service) {
        this.service = service;
    }

    /** Body of the hold request: the seats wanted. */
    record HoldRequest(List<String> seatIds) {
    }

    /** Body of the confirm request: the payment the caller made. */
    record ConfirmRequest(String paymentRef) {
    }

    /** JSON of a hold. */
    record HoldResponse(String holdId, String showId, List<String> seatIds, Instant expiresAt) {
    }

    /** JSON of a booking. */
    record BookingResponse(String bookingId, String holdId, String showId, List<String> seatIds, String paymentRef, Instant confirmedAt) {
    }

    /**
     * Holds the seats, all or none. 201 with the hold on success. 409 listing every unavailable seat in the {@code unavailableSeats} property if any seat is taken. 404 for an unknown show, 400 for unknown or empty or duplicate seats.
     */
    @PostMapping("/shows/{showId}/holds")
    ResponseEntity<?> hold(@PathVariable String showId, @RequestBody HoldRequest request) {
        if (request.seatIds() == null) {
            return problem(HttpStatus.BAD_REQUEST, "seatIds is required");
        }
        Hold h;
        try {
            h = service.hold(showId, request.seatIds());
        } catch (SeatUnavailableException e) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
            p.setProperty("unavailableSeats", e.unavailableSeats());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(p);
        } catch (ShowNotFoundException e) {
            return problem(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (UnknownSeatException e) {
            return problem(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown by the core for an empty or duplicated seat list. Caught here, around the domain call only.
            return problem(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.created(URI.create("/api/booking/holds/" + h.holdId()))
                .body(new HoldResponse(h.holdId(), h.showId(), h.seatIds(), h.expiresAt()));
    }

    /**
     * Confirms a hold after payment. The {@code Idempotency-Key} header is required (400 if missing). 201 the first time, 200 with the same body on a replay, 422 if the key was used with a different body (IETF Idempotency-Key draft §2.7), 409 if the hold is already booked, 410 if the hold has expired, 404 for an unknown hold.
     */
    @PostMapping("/holds/{holdId}/confirm")
    ResponseEntity<?> confirm(@PathVariable String holdId, @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key, @RequestBody ConfirmRequest request) {
        if (key == null || key.isBlank()) {
            return problem(HttpStatus.BAD_REQUEST, "the Idempotency-Key header is required");
        }
        if (request.paymentRef() == null || request.paymentRef().isBlank()) {
            return problem(HttpStatus.BAD_REQUEST, "paymentRef is required");
        }
        ConfirmResult r;
        try {
            r = service.confirm(holdId, key, request.paymentRef());
        } catch (HoldNotFoundException e) {
            return problem(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (HoldExpiredException e) {
            return problem(HttpStatus.GONE, e.getMessage());
        } catch (IdempotencyConflictException e) {
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        } catch (AlreadyConfirmedException e) {
            return problem(HttpStatus.CONFLICT, e.getMessage());
        }
        Booking b = r.booking();
        BookingResponse body = new BookingResponse(b.bookingId(), b.holdId(), b.showId(), b.seatIds(), b.paymentRef(), b.confirmedAt());
        return ResponseEntity.status(r.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /** Releases a hold. 204 on success, and also when it was already released or expired. 404 for an unknown hold, 409 if it is already booked. */
    @DeleteMapping("/holds/{holdId}")
    ResponseEntity<?> release(@PathVariable String holdId) {
        try {
            service.release(holdId);
        } catch (HoldNotFoundException e) {
            return problem(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (AlreadyConfirmedException e) {
            return problem(HttpStatus.CONFLICT, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    /** Builds an RFC 9457 problem response with the given status and detail. */
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
