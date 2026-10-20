package com.learning.hotel.controller;

import com.learning.hotel.dto.BookingRequest;
import com.learning.hotel.dto.BookingResponse;
import com.learning.hotel.dto.RoomRequest;
import com.learning.hotel.model.Booking;
import com.learning.hotel.model.Room;
import com.learning.hotel.model.RoomType;
import com.learning.hotel.service.HotelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hotel")
@Tag(name = "Hotel", description = "Hotel management operations")
public class HotelController {

    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @PostMapping("/rooms")
    @Operation(summary = "Add a room")
    public ResponseEntity<String> addRoom(@Valid @RequestBody RoomRequest request) {
        Room room = new Room(request.roomNumber(), request.type(), request.pricePerNight());
        hotelService.addRoom(room);
        return ResponseEntity.status(HttpStatus.CREATED).body("Room added successfully");
    }

    @GetMapping("/rooms")
    @Operation(summary = "Get all rooms")
    public ResponseEntity<List<Room>> getAllRooms() {
        return ResponseEntity.ok(hotelService.getAllRooms());
    }

    @GetMapping("/rooms/available")
    @Operation(summary = "Get available rooms")
    public ResponseEntity<List<Room>> getAvailableRooms(@RequestParam(required = false) RoomType type) {
        return ResponseEntity.ok(hotelService.getAvailableRooms(type));
    }

    @GetMapping("/rooms/{roomNumber}")
    @Operation(summary = "Get room details")
    public ResponseEntity<Room> getRoom(@PathVariable String roomNumber) {
        Room room = hotelService.getRoom(roomNumber);
        return room != null ? ResponseEntity.ok(room) : ResponseEntity.notFound().build();
    }

    @PostMapping("/bookings")
    @Operation(summary = "Book a room")
    public ResponseEntity<?> bookRoom(@Valid @RequestBody BookingRequest request) {
        try {
            Booking booking = hotelService.bookRoom(
                request.roomNumber(), request.guestName(),
                request.checkInDate(), request.checkOutDate()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(booking));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/bookings/{bookingId}")
    @Operation(summary = "Get booking details")
    public ResponseEntity<BookingResponse> getBooking(@PathVariable String bookingId) {
        Booking booking = hotelService.getBooking(bookingId);
        return booking != null ? ResponseEntity.ok(toResponse(booking))
                                : ResponseEntity.notFound().build();
    }

    @PostMapping("/bookings/{bookingId}/checkout")
    @Operation(summary = "Checkout from room")
    public ResponseEntity<?> checkoutRoom(@PathVariable String bookingId) {
        try {
            hotelService.checkoutRoom(bookingId);
            return ResponseEntity.ok("Checkout successful");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Cancel booking")
    public ResponseEntity<?> cancelBooking(@PathVariable String bookingId) {
        try {
            hotelService.cancelBooking(bookingId);
            return ResponseEntity.ok("Booking cancelled successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
            booking.getBookingId(),
            booking.getGuestName(),
            booking.getRoomNumber(),
            booking.getCheckInDate(),
            booking.getCheckOutDate(),
            booking.getTotalCost(),
            booking.getStatus()
        );
    }
}
