package de.agiledev.agent.web;

import com.google.genai.errors.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeFailureDiagnosticTest {
    @Test
    void nestedMcpFailureRemainsCompactAndIdentifiesServer() {
        var mcp = new de.agiledev.agent.diagnostics.McpToolLoadingFailure("expense-mcp",
                "https://expense.example/mcp", new IllegalStateException("HTTP 401 Unauthorized"));
        var diagnostic = SafeFailureDiagnostic.describe(new java.util.concurrent.ExecutionException(mcp));
        assertTrue(diagnostic.contains("server=expense-mcp"));
        assertTrue(diagnostic.contains("HTTP 401 Unauthorized"));
        assertFalse(diagnostic.contains("\n  at"));
    }
    @Test
    void reportsNestedProviderStatusWithoutLoggingCredentialsOrMessages() {
        var provider = new ApiException(403, "PERMISSION_DENIED", "secret-api-key");
        var failure = new IllegalStateException("Bearer secret-access-token", provider);
        var diagnostic = SafeFailureDiagnostic.describe(failure, "secret-api-key");
        assertTrue(diagnostic.contains("IllegalStateException"));
        assertTrue(diagnostic.contains("Gemini HTTP 403"));
        assertTrue(diagnostic.contains("SafeFailureDiagnosticTest"));
        assertFalse(diagnostic.contains("secret-api-key"));
        assertFalse(diagnostic.contains("secret-access-token"));
    }

    @Test
    void includesGoogleModelErrorButRedactsCredentialFormatsAndLineBreaks() {
        var provider = new ApiException(404, "NOT_FOUND",
                "Model models/example not found for generateContent.\n"
                + "key=query-secret&x=1 {\"x-goog-api-key\":\"header-secret\"} "
                + "Bearer bearer-secret AIzaExampleGoogleKey custom-secret");
        var diagnostic = SafeFailureDiagnostic.describe(provider, "custom-secret");
        assertTrue(diagnostic.contains("Model models/example not found for generateContent."));
        for (String secret : new String[]{"query-secret", "header-secret", "bearer-secret",
                "AIzaExampleGoogleKey", "custom-secret"}) {
            assertFalse(diagnostic.contains(secret), secret);
        }
        assertFalse(diagnostic.contains(".\nkey="));
    }

    @Test
    void terminatesForCyclicCauses() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertTrue(SafeFailureDiagnostic.describe(first).length() < 100_000);
    }
}
