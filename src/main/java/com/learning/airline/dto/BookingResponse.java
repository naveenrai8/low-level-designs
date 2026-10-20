package com.learning.airline.dto;

import com.learning.airline.model.BookingStatus;

public record BookingResponse(
    String bookingId,
    String flightNumber,
    String passengerName,
    int numberOfSeats,
    double totalCost,
    BookingStatus status
) {}
