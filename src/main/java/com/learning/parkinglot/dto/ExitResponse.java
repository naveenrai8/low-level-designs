package com.learning.parkinglot.dto;

import java.time.Instant;

/**
 * Response DTO for vehicle exit.
 *
 * @param ticketId the ticket ID
 * @param fee the calculated parking fee
 * @param exitTime the exit timestamp
 * @param message success message
 */
public record ExitResponse(
        String ticketId,
        double fee,
        Instant exitTime,
        String message
) {
    public static ExitResponse of(String ticketId, double fee, Instant exitTime) {
        return new ExitResponse(
                ticketId,
                fee,
                exitTime,
                String.format("Thank you! Your parking fee is $%.2f", fee)
        );
    }
}
