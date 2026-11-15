package com.example.travel.config;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component("travelAuthorization")
public class TravelAuthorization {

    private final com.example.travel.inspector.ToolDecisionPublisher inspector;

    public TravelAuthorization(com.example.travel.inspector.ToolDecisionPublisher inspector) {
        this.inspector = inspector;
    }

    public boolean canRead(Authentication auth, String tool) {
        return allowed(auth, "SCOPE_travel.read", tool);
    }

    public boolean canBook(Authentication auth, String tool) {
        return allowed(auth, "SCOPE_travel.book", tool);
    }

    private boolean allowed(Authentication auth, String scope, String tool) {
        boolean allowed = auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> Objects.equals(a.getAuthority(), scope));
        inspector.publish(auth, tool, allowed, allowed ? "Required scope present: " + scope.substring(6)
                : "Missing authentication or required scope: " + scope.substring(6));
        return allowed;
    }
}
