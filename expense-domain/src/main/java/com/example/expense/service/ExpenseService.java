package com.example.expense.service;

import com.example.expense.domain.Expense;
import com.example.expense.domain.ExpenseStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ExpenseService {
    private final Map<String, Expense> expenses = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(100);

    public ExpenseService() {
        reset();
    }

    public synchronized void reset() {
        expenses.clear();
        counter.set(100);
        expenses.put("EXP-8000", new Expense("EXP-8000", "BOOK-SEED", new BigDecimal("8000.00"), "EUR", "alice", ExpenseStatus.PENDING, Instant.parse("2026-11-01T10:00:00Z"), null));
    }

    public synchronized Expense create(String bookingId, BigDecimal amount, String owner) {
        if (bookingId == null || bookingId.isBlank()) throw new IllegalArgumentException("bookingId required");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        String id = "EXP-" + counter.incrementAndGet();
        Expense e = new Expense(id, bookingId, amount, "EUR", owner, ExpenseStatus.PENDING, Instant.now(), null);
        expenses.put(id, e);
        return e;
    }

    public Expense get(String id) {
        Expense e = expenses.get(id);
        if (e == null) throw new IllegalArgumentException("Unknown expense: " + id);
        return e;
    }

    public synchronized Expense approve(String id, String approver) {
        Expense old = get(id);
        if (old.status() != ExpenseStatus.PENDING) throw new IllegalStateException("Already approved");
        Expense e = new Expense(old.id(), old.bookingId(), old.amount(), old.currency(), old.owner(), ExpenseStatus.APPROVED, old.createdAt(), approver);
        expenses.put(id, e);
        return e;
    }
}
