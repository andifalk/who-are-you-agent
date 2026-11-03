package com.example.travel;

import com.example.travel.service.TravelService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TravelServiceTest {
    @Test
    void cheapestFlightAndBooking() {
        var service = new TravelService();
        var date = LocalDate.of(2026, 11, 23);
        var flights = service.search("STR", "BER", date);
        assertEquals("FL-100", flights.getFirst().id());
        var booking = service.book("FL-100", "Alice", date);
        assertEquals("CONFIRMED", booking.status());
        assertEquals(booking, service.getBooking(booking.id()));
        service.reset();
        assertThrows(IllegalArgumentException.class, () -> service.getBooking(booking.id()));
    }
}
