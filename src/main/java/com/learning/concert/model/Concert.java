package com.learning.concert.model;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

public class Concert {
    private final String concertId;
    private final String artist;
    private final String venue;
    private final LocalDateTime dateTime;
    private final ConcurrentHashMap<String, Seat> seats;

    public Concert(String concertId, String artist, String venue, LocalDateTime dateTime, int totalSeats) {
        this.concertId = concertId;
        this.artist = artist;
        this.venue = venue;
        this.dateTime = dateTime;
        this.seats = new ConcurrentHashMap<>();
        initializeSeats(totalSeats);
    }

    private void initializeSeats(int total) {
        for (int i = 1; i <= total; i++) {
            seats.put("SEAT-" + i, new Seat("SEAT-" + i, 50.0));
        }
    }

    public synchronized boolean bookSeat(String seatId) {
        Seat seat = seats.get(seatId);
        return seat != null && seat.book();
    }

    public String getConcertId() { return concertId; }
    public String getArtist() { return artist; }
    public String getVenue() { return venue; }
    public LocalDateTime getDateTime() { return dateTime; }
    public ConcurrentHashMap<String, Seat> getSeats() { return new ConcurrentHashMap<>(seats); }
}
