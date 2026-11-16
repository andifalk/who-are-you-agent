package de.agiledev.agent.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TokenClaimDiagnosticTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private String token(String payload) {
        return "eyJhbGciOiJSUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void reportsOnlyIssuedClaimsIncludingMultipleAudiences() {
        String token = token("""
                {"iss":"https://issuer.example/realms/travel","aud":["travel-mcp","expense-mcp"],
                 "scope":"travel.read expenses.read","sub":"private-user","client_secret":"hidden-secret"}
                """);
        String result = TokenClaimDiagnostic.describe(mapper, token);
        assertTrue(result.contains("iss=\"https://issuer.example/realms/travel\""));
        assertTrue(result.contains("aud=[\"travel-mcp\", \"expense-mcp\"]"));
        assertTrue(result.contains("scope=\"travel.read expenses.read\""));
        assertFalse(result.contains("private-user"));
        assertFalse(result.contains("hidden-secret"));
        assertFalse(result.contains(token));
        var snapshot = TokenClaimDiagnostic.read(mapper, token);
        assertTrue(snapshot.available());
        assertEquals("https://issuer.example/realms/travel", snapshot.issuer());
        assertEquals("[\"travel-mcp\", \"expense-mcp\"]", snapshot.audience());
        assertEquals("travel.read expenses.read", snapshot.scopes());
    }

    @Test
    void handlesStringAudienceAndMissingClaims() {
        String result = TokenClaimDiagnostic.describe(mapper, token("{\"aud\":\"expense-mcp\"}"));
        assertTrue(result.contains("aud=\"expense-mcp\""));
        assertTrue(result.contains("iss=<missing>"));
        assertTrue(result.contains("scope=<missing>"));
    }

    @Test
    void redactsSecretsAndLineBreaksEvenInsideSelectedClaims() {
        String result = TokenClaimDiagnostic.describe(mapper,
                token("{\"iss\":\"secret-value\",\"scope\":\"expenses.read\\nBearer access-secret\"}"), "secret-value");
        assertFalse(result.contains("secret-value"));
        assertFalse(result.contains("access-secret"));
        assertFalse(result.contains("\n"));
        var snapshot = TokenClaimDiagnostic.read(mapper,
                token("{\"iss\":\"secret-value\",\"aud\":\"secret-value\",\"scope\":\"secret-value\"}"), "secret-value");
        assertFalse(snapshot.issuer().contains("secret-value"));
        assertFalse(snapshot.audience().contains("secret-value"));
        assertFalse(snapshot.scopes().contains("secret-value"));
    }

    @Test
    void malformedTokensNeverExposeInputOrThrow() {
        for (String token : new String[]{"opaque-secret", "header.bad-json.signature", token("null"), token("[]")})
            assertEquals("claims=unavailable", TokenClaimDiagnostic.describe(mapper, token));
    }
}
