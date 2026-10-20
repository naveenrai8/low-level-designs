package com.learning.concert.service;

import com.learning.concert.model.Concert;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConcertService {
    private final ConcurrentHashMap<String, Concert> concerts = new ConcurrentHashMap<>();

    public Concert createConcert(String concertId, String artist, String venue, LocalDateTime dateTime, int totalSeats) {
        Concert concert = new Concert(concertId, artist, venue, dateTime, totalSeats);
        concerts.put(concertId, concert);
        return concert;
    }

    public boolean bookSeat(String concertId, String seatId) {
        Concert concert = concerts.get(concertId);
        return concert != null && concert.bookSeat(seatId);
    }

    public Concert getConcert(String concertId) {
        return concerts.get(concertId);
    }

    public List<Concert> getAllConcerts() {
        return List.copyOf(concerts.values());
    }
}
