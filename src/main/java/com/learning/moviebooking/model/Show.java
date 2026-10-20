package com.learning.moviebooking.model;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

public class Show {
    private final String showId;
    private final String movieName;
    private final String theater;
    private final LocalDateTime showTime;
    private final ConcurrentHashMap<String, Seat> seats;

    public Show(String showId, String movieName, String theater, LocalDateTime showTime, int rows, int cols) {
        this.showId = showId;
        this.movieName = movieName;
        this.theater = theater;
        this.showTime = showTime;
        this.seats = new ConcurrentHashMap<>();
        initializeSeats(rows, cols);
    }

    private void initializeSeats(int rows, int cols) {
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                String seatId = (char)('A' + i) + String.valueOf(j + 1);
                seats.put(seatId, new Seat(seatId, i < 3 ? SeatType.PREMIUM : SeatType.NORMAL));
            }
        }
    }

    public synchronized boolean bookSeat(String seatId) {
        Seat seat = seats.get(seatId);
        return seat != null && seat.book();
    }

    public String getShowId() { return showId; }
    public String getMovieName() { return movieName; }
    public ConcurrentHashMap<String, Seat> getSeats() { return seats; }
}

class Seat {
    private final String seatId;
    private final SeatType type;
    private volatile boolean booked;

    public Seat(String seatId, SeatType type) {
        this.seatId = seatId;
        this.type = type;
        this.booked = false;
    }

    public synchronized boolean book() {
        if (booked) return false;
        booked = true;
        return true;
    }

    public boolean isBooked() { return booked; }
    public SeatType getType() { return type; }
}
