package com.example.travel.mcp;

import com.example.travel.domain.Booking;
import com.example.travel.domain.Flight;
import com.example.travel.service.TravelService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class TravelTools {
    private final TravelService service;

    public TravelTools(TravelService service) {
        this.service = service;
    }

    @McpTool(name = "search_flights", description = "Search deterministic demo flights. Origin STR (Stuttgart), destination BER (Berlin), date ISO YYYY-MM-DD.")
    @PreAuthorize("@travelAuthorization.canRead(authentication)")
    public List<Flight> searchFlights(
            @ToolParam(description = "Origin IATA code, e.g. STR") String origin,
            @ToolParam(description = "Destination IATA code, e.g. BER") String destination,
            @ToolParam(description = "Date YYYY-MM-DD") String date) {
        return service.search(origin, destination, LocalDate.parse(date));
    }

    @McpTool(name = "book_flight", description = "Book a demo flight and return a booking ID. No real reservation is made.")
    @PreAuthorize("@travelAuthorization.canBook(authentication)")
    public Booking bookFlight(@ToolParam(description = "Flight ID, e.g. FL-100") String flightId,
                              @ToolParam(description = "Traveler name") String traveler,
                              @ToolParam(description = "Date YYYY-MM-DD") String date) {
        return service.book(flightId, traveler, LocalDate.parse(date));
    }

    @McpTool(name = "get_booking", description = "Get a booking by booking ID")
    @PreAuthorize("@travelAuthorization.canRead(authentication)")
    public Booking getBooking(@ToolParam(description = "Booking ID") String bookingId) {
        return service.getBooking(bookingId);
    }
}
