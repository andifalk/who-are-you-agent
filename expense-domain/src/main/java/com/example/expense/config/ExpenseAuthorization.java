package com.example.expense.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ExpenseAuthorization {

    public ExpenseAuthorization() {
    }

    public String require(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated())
            throw new AccessDeniedException("JWT authentication required");
        if (auth.getAuthorities().stream().noneMatch(a -> Objects.equals(a.getAuthority(), "SCOPE_" + scope)))
            throw new AccessDeniedException("Missing scope: " + scope);
        return jwt.getToken().getSubject();
    }
}
