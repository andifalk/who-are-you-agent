package com.example.expense.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Expense(String id, String bookingId, BigDecimal amount, String currency,
                      String owner, ExpenseStatus status, Instant createdAt, String approvedBy) {
}
