package com.example.travel.service;

import com.example.travel.domain.Booking;
import com.example.travel.domain.Flight;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TravelService {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(100);

    public List<Flight> search(String origin, String destination, LocalDate date) {
        if (!"STR".equalsIgnoreCase(origin) || !"BER".equalsIgnoreCase(destination)) return List.of();
        return List.of(new Flight("FL-100", "STR", "BER", date, "Demo Air", new BigDecimal("149.00")), new Flight("FL-101", "STR", "BER", date, "Example Wings", new BigDecimal("219.00")), new Flight("FL-102", "STR", "BER", date, "Sample Jet", new BigDecimal("179.00")));
    }

    public Booking book(String flightId, String traveler, LocalDate date) {
        Flight flight = search("STR", "BER", date).stream().filter(f -> f.id().equals(flightId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown flight ID: " + flightId));
        if (traveler == null || traveler.isBlank()) throw new IllegalArgumentException("Traveler required");
        String id = "BOOK-" + sequence.incrementAndGet();
        Booking booking = new Booking(id, flight.id(), traveler, flight.priceEur(), "CONFIRMED");
        bookings.put(id, booking);
        return booking;
    }

    public Booking getBooking(String id) {
        Booking b = bookings.get(id);
        if (b == null) throw new IllegalArgumentException("Unknown booking: " + id);
        return b;
    }

    public synchronized void reset() {
        bookings.clear();
        sequence.set(100);
    }
}
