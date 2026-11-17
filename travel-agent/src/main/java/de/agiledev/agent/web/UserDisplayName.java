package de.agiledev.agent.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/** Presentation only: never changes the principal name used for authentication or token lookup. */
public final class UserDisplayName {
    private UserDisplayName() { }

    public static String of(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal) {
            String first = text(principal.getAttribute("given_name"));
            String last = text(principal.getAttribute("family_name"));
            String full = (first + " " + last).trim();
            if (!full.isEmpty()) return full;
            String name = text(principal.getAttribute("name"));
            if (!name.isEmpty()) return name;
            String username = text(principal.getAttribute("preferred_username"));
            if (!username.isEmpty()) return username;
        }
        return authentication.getName();
    }

    private static String text(Object value) {
        return value instanceof String text ? text.trim() : "";
    }
}
