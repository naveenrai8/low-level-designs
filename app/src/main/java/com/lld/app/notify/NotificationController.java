package com.lld.app.notify;

import com.lld.notify.ChannelType;
import com.lld.notify.IdempotencyConflictException;
import com.lld.notify.InvalidRequestException;
import com.lld.notify.Notification;
import com.lld.notify.NotificationRequest;
import com.lld.notify.NotificationService;
import com.lld.notify.Priority;
import com.lld.notify.SubmitResult;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Submit and look up notifications over HTTP. HTTP adds 202 (accepted, not yet sent), the {@code Idempotency-Key} header and 422 for key reuse.
 *
 * <p>Domain exceptions are translated here, around exactly the core call. There is no global handler for IllegalArgumentException.
 */
@RestController
@RequestMapping("/notifications")
class NotificationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final NotificationService service;

    /** Creates the controller around the service bean. */
    NotificationController(NotificationService service) {
        this.service = service;
    }

    /** Body of the submit request. */
    record SubmitRequest(String userId, ChannelType channel, String template, Map<String, String> variables, Priority priority) {
    }

    /** JSON of an accepted submit. */
    record SubmitResponse(String id, boolean replay) {
    }

    /** JSON of a notification's status. */
    record StatusResponse(String id, String userId, ChannelType channel, String status, int attempts, String reason, Instant nextAttemptAt) {
    }

    /**
     * Accepts a notification. The {@code Idempotency-Key} header is required (400 if missing). 202 the first time, 200 with the same id on a repeat, 422 if the key was used with a different body (IETF Idempotency-Key draft section 2.7; the draft's 409 is for a retry while the first request is still running, which cannot happen because submit finishes before it returns), 400 for any invalid request (unknown user, unknown template, missing template variable, missing field).
     */
    @PostMapping
    ResponseEntity<?> submit(@RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key, @RequestBody SubmitRequest body) {
        if (key == null || key.isBlank()) {
            return problem(HttpStatus.BAD_REQUEST, "the Idempotency-Key header is required");
        }
        SubmitResult r;
        try {
            NotificationRequest request = new NotificationRequest(body.userId(), body.channel(), body.template(), body.variables(), body.priority());
            r = service.submit(key, request);
        } catch (InvalidRequestException e) {
            return problem(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IdempotencyConflictException e) {
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        HttpStatus status = r.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).location(URI.create("/notifications/" + r.id())).body(new SubmitResponse(r.id(), r.replay()));
    }

    /** Returns the status of one notification, or 404 if the id is unknown. */
    @GetMapping("/{id}")
    ResponseEntity<?> get(@PathVariable String id) {
        return service.find(id)
                .<ResponseEntity<?>>map(n -> ResponseEntity.ok(toResponse(n)))
                .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "unknown notification: " + id));
    }

    /** Converts a core snapshot to JSON. The body text is left out on purpose: it may contain personal data. */
    private static StatusResponse toResponse(Notification n) {
        return new StatusResponse(n.id(), n.userId(), n.channel(), n.status().name(), n.attempts(), n.reason(), n.nextAttemptAt());
    }

    /** Builds an RFC 9457 problem response with the given status and detail. */
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
