package com.example.travel.config;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component("travelAuthorization")
public class TravelAuthorization {

    public TravelAuthorization() {
    }

    public boolean canRead(Authentication auth) {
        return allowed(auth, "SCOPE_travel.read");
    }

    public boolean canBook(Authentication auth) {
        return allowed(auth, "SCOPE_travel.book");
    }

    private boolean allowed(Authentication auth, String scope) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream().anyMatch(a -> Objects.equals(a.getAuthority(), scope));
    }
}
