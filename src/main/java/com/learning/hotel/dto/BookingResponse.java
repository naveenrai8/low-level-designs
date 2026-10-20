package com.learning.hotel.dto;

import com.learning.hotel.model.BookingStatus;
import java.time.LocalDate;

public record BookingResponse(
    String bookingId,
    String guestName,
    String roomNumber,
    LocalDate checkInDate,
    LocalDate checkOutDate,
    double totalCost,
    BookingStatus status
) {}
