package com.example.expense;

import com.example.expense.config.ExpenseAuthorization;
import com.example.expense.inspector.ToolDecisionPublisher;
import com.example.expense.mcp.ExpenseTools;
import com.example.expense.service.ExpenseService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolAuthorizationTest {
    @Test
    void approvalDenialNeverCallsBusinessServiceAndAllowedCallReportsDecision() {
        var publisher = mock(ToolDecisionPublisher.class);
        var service = mock(ExpenseService.class);
        var tools = new ExpenseTools(service, new ExpenseAuthorization(publisher));
        var jwt = Jwt.withTokenValue("secret").header("alg", "RS256").subject("alice").build();
        try {
            var denied = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_expenses.read")));
            SecurityContextHolder.getContext().setAuthentication(denied);
            assertThrows(AccessDeniedException.class, () -> tools.approveExpense("EXP-8000"));
            verify(publisher).publish(eq(denied), eq("approve_expense"), eq(false), anyString());
            verifyNoInteractions(service);
            var allowed = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_expenses.approve")));
            SecurityContextHolder.getContext().setAuthentication(allowed);
            tools.approveExpense("EXP-8000");
            verify(publisher).publish(eq(allowed), eq("approve_expense"), eq(true), anyString());
            verify(service).approve("EXP-8000", "alice");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
