package de.agiledev.agent.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class DelegatedTokenProviderErrorTest {
    private final DelegatedTokenProvider provider = new DelegatedTokenProvider(null, null, null, new ObjectMapper());

    @Test
    void explainsClientErrorsWithoutEchoingResponseSecrets() {
        var error = assertThrows(TokenAcquisitionException.class, () -> provider.parseResponse(
                "CLIENT_CREDENTIALS", 401,
                "{\"error\":\"invalid_client\",\"error_description\":\"secret-client-password\"}"));
        assertTrue(error.getMessage().contains("CLIENT_CREDENTIALS: HTTP 401 invalid_client"));
        assertTrue(error.getMessage().contains("client secret"));
        assertFalse(error.getMessage().contains("secret-client-password"));
        assertNull(error.getCause());
    }

    @Test
    void rejectsMissingAndNonStringTokens() {
        for (String body : new String[]{"{}", "null", "{\"access_token\":null}", "{\"access_token\":123}"}) {
            assertThrows(TokenAcquisitionException.class,
                    () -> provider.parseResponse("JWT_BEARER_GRANT", 200, body));
        }
    }

    @Test
    void handlesProxyErrorsAndInvalidJsonWithoutEchoingBodies() {
        var proxy = assertThrows(TokenAcquisitionException.class,
                () -> provider.parseResponse("TOKEN_EXCHANGE", 502, "<html>secret</html>"));
        assertTrue(proxy.getMessage().contains("HTTP 502"));
        assertFalse(proxy.getMessage().contains("<html>"));
        assertThrows(TokenAcquisitionException.class,
                () -> provider.parseResponse("TOKEN_EXCHANGE", 200, "invalid-json"));
        var unknown = DelegatedTokenProvider.oauthFailure("TOKEN_EXCHANGE", 400, "secret-token");
        assertFalse(unknown.getMessage().contains("secret-token"));
    }

    @Test
    void acceptsValidTokenResponses() {
        assertEquals("token", provider.parseResponse("TOKEN_EXCHANGE", 200,
                "{\"access_token\":\"token\"}").path("access_token").asString());
    }
}
