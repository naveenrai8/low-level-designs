package com.learning.parkinglot.dto;

import java.time.Instant;

/**
 * Standard error response DTO.
 *
 * @param timestamp when the error occurred
 * @param status HTTP status code
 * @param error error type
 * @param message error message
 * @param path request path
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path);
    }
}
