package com.learning.hotel.service;

import com.learning.hotel.model.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class HotelService {
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Booking> bookings = new ConcurrentHashMap<>();

    public void addRoom(Room room) {
        rooms.put(room.getRoomNumber(), room);
    }

    public List<Room> getAvailableRooms(RoomType type) {
        return rooms.values().stream()
                .filter(room -> room.getStatus() == RoomStatus.AVAILABLE &&
                               (type == null || room.getType() == type))
                .collect(Collectors.toList());
    }

    public Booking bookRoom(String roomNumber, String guestName,
                           LocalDate checkInDate, LocalDate checkOutDate) throws Exception {
        Room room = rooms.get(roomNumber);
        if (room == null) {
            throw new Exception("Room not found");
        }

        if (!room.book()) {
            throw new Exception("Room not available");
        }

        long nights = ChronoUnit.DAYS.between(checkInDate, checkOutDate);
        if (nights <= 0) {
            room.checkout();
            throw new Exception("Invalid date range");
        }

        double totalCost = nights * room.getPricePerNight();
        Booking booking = new Booking(guestName, roomNumber, checkInDate, checkOutDate, totalCost);
        bookings.put(booking.getBookingId(), booking);

        return booking;
    }

    public void checkoutRoom(String bookingId) throws Exception {
        Booking booking = bookings.get(bookingId);
        if (booking == null) {
            throw new Exception("Booking not found");
        }

        Room room = rooms.get(booking.getRoomNumber());
        if (room != null) {
            room.checkout();
        }

        booking.complete();
    }

    public void cancelBooking(String bookingId) throws Exception {
        Booking booking = bookings.get(bookingId);
        if (booking == null) {
            throw new Exception("Booking not found");
        }

        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new Exception("Cannot cancel completed booking");
        }

        Room room = rooms.get(booking.getRoomNumber());
        if (room != null) {
            room.checkout();
        }

        booking.cancel();
    }

    public Booking getBooking(String bookingId) {
        return bookings.get(bookingId);
    }

    public Room getRoom(String roomNumber) {
        return rooms.get(roomNumber);
    }

    public List<Room> getAllRooms() {
        return List.copyOf(rooms.values());
    }
}
