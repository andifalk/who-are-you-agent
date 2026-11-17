package de.agiledev.agent.web;

import de.agiledev.agent.auth.SessionModeStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserDisplayNameTest {
    private OAuth2AuthenticationToken user(Map<String, Object> claims) {
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities, claims, "sub"),
                authorities, "corporate");
    }

    @Test
    void showsFirstAndLastNameWithoutChangingAuthenticationName() {
        var auth = user(Map.of("sub", "user-uuid", "given_name", " Alice ", "family_name", " Example ",
                "name", "Different Name"));
        var controller = new AgentController(null, null, new SessionModeStore(), null);
        assertEquals("Alice Example", controller.session(new MockHttpSession(), auth).get("user"));
        assertEquals("user-uuid", auth.getName());
        assertEquals("user-uuid", auth.getPrincipal().getAttribute("sub"));
    }

    @Test
    void usesNameOrUsernameWhenIndividualNamesAreMissing() {
        assertEquals("Alice Example", UserDisplayName.of(user(Map.of("sub", "uuid", "name", "Alice Example"))));
        assertEquals("alice", UserDisplayName.of(user(Map.of("sub", "uuid", "preferred_username", "alice"))));
    }

    @Test
    void keepsOriginalIdentifierAsLastFallback() {
        assertEquals("uuid", UserDisplayName.of(user(Map.of("sub", "uuid"))));
    }
}
