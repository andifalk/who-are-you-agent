package com.example.expense.mcp;

import com.example.expense.config.ExpenseAuthorization;
import com.example.expense.domain.Expense;
import com.example.expense.service.ExpenseService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class ExpenseTools {
    private final ExpenseService service;
    private final ExpenseAuthorization authorization;

    public ExpenseTools(ExpenseService service, ExpenseAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @McpTool(name = "create_expense", description = "Create a pending expense report for a booked flight. Amount in EUR.")
    public Expense createExpense(@McpToolParam(description = "Booking ID", required = true) String bookingId,
                                 @McpToolParam(description = "Expense amount in EUR", required = true) BigDecimal amount) {
        String owner = authorization.require("expenses.create");
        return service.create(bookingId, amount, owner);
    }

    @McpTool(name = "get_expense", description = "Get expense details by expense ID.")
    public Expense getExpense(@McpToolParam(description = "Expense ID", required = true) String expenseId) {
        String subject = authorization.require("expenses.read");
        Expense e = service.get(expenseId);
        // A real system would resolve subject -> business user and enforce ownership/finance roles.
        return e;
    }

    @McpTool(name = "approve_expense", description = "Approve an existing expense report. Requires finance approval permission.")
    public Expense approveExpense(@McpToolParam(description = "Expense ID", required = true) String expenseId) {
        String approver = authorization.require("expenses.approve");
        return service.approve(expenseId, approver);
    }
}
