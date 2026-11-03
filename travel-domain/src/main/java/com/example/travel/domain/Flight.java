package com.example.travel.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Flight(
        String id, String origin, String destination,
        LocalDate departureDate, String airline, BigDecimal priceEur
) {
}
