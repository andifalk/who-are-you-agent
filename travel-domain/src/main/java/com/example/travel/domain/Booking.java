package com.example.travel.domain;

import java.math.BigDecimal;

public record Booking(
        String id, String flightId, String traveler,
        BigDecimal amountEur, String status
) {
}
