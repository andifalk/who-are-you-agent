package com.example.expense.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ExpenseAuthorization {

    private final com.example.expense.inspector.ToolDecisionPublisher inspector;

    public ExpenseAuthorization(com.example.expense.inspector.ToolDecisionPublisher inspector) {
        this.inspector = inspector;
    }

    public String require(String scope, String tool) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated()) {
            inspector.publish(auth, tool, false, "JWT authentication required");
            throw new AccessDeniedException("JWT authentication required");
        }
        if (auth.getAuthorities().stream().noneMatch(a -> Objects.equals(a.getAuthority(), "SCOPE_" + scope))) {
            inspector.publish(auth, tool, false, "Missing scope: " + scope);
            throw new AccessDeniedException("Missing scope: " + scope);
        }
        inspector.publish(auth, tool, true, "Required scope present: " + scope);
        return jwt.getToken().getSubject();
    }
}
