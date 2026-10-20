package com.learning.airline.service;

import com.learning.airline.model.Flight;
import com.learning.airline.model.FlightBooking;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AirlineService {
    private final ConcurrentHashMap<String, Flight> flights = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FlightBooking> bookings = new ConcurrentHashMap<>();

    public void addFlight(Flight flight) {
        flights.put(flight.getFlightNumber(), flight);
    }

    public List<Flight> searchFlights(String source, String destination) {
        return flights.values().stream()
                .filter(flight -> flight.getSource().equalsIgnoreCase(source) &&
                                 flight.getDestination().equalsIgnoreCase(destination) &&
                                 flight.getAvailableSeats() > 0)
                .collect(Collectors.toList());
    }

    public FlightBooking bookFlight(String flightNumber, String passengerName, int numberOfSeats) throws Exception {
        Flight flight = flights.get(flightNumber);
        if (flight == null) {
            throw new Exception("Flight not found");
        }

        if (flight.getAvailableSeats() < numberOfSeats) {
            throw new Exception("Not enough seats available");
        }

        for (int i = 0; i < numberOfSeats; i++) {
            if (!flight.bookSeat()) {
                throw new Exception("Failed to book all seats");
            }
        }

        double totalCost = numberOfSeats * flight.getPrice();
        FlightBooking booking = new FlightBooking(flightNumber, passengerName, numberOfSeats, totalCost);
        bookings.put(booking.getBookingId(), booking);

        return booking;
    }

    public FlightBooking getBooking(String bookingId) {
        return bookings.get(bookingId);
    }

    public Flight getFlight(String flightNumber) {
        return flights.get(flightNumber);
    }

    public List<Flight> getAllFlights() {
        return List.copyOf(flights.values());
    }
}
