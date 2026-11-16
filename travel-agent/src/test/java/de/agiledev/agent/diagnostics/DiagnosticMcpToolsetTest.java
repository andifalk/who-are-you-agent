package de.agiledev.agent.diagnostics;

import com.google.adk.tools.BaseTool;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticMcpToolsetTest {
    @Test
    void identifiesServerAndNestedHttpReasonWithoutCredentials() {
        var root = new IllegalStateException("HTTP 401 Unauthorized Authorization: Bearer secret-token "
                + "client_secret=client-password key=api-secret");
        var failure = new RuntimeException("MCP initialize failed", root);
        var diagnostic = assertThrows(McpToolLoadingFailure.class, () -> DiagnosticMcpToolset.diagnose(
                () -> Flowable.error(failure), "expense-mcp",
                "https://user:url-password@expense.example/mcp?key=url-key#fragment",
                "secret-token", "client-password", "api-secret").blockingFirst());
        assertTrue(diagnostic.getMessage().contains("server=expense-mcp"));
        assertTrue(diagnostic.getMessage().contains("endpoint=https://expense.example/mcp"));
        assertTrue(diagnostic.getMessage().contains("HTTP 401 Unauthorized"));
        for (String secret : new String[]{"secret-token", "client-password", "api-secret", "url-password", "url-key"})
            assertFalse(diagnostic.getMessage().contains(secret), secret);
        assertNull(diagnostic.getCause());
    }

    @Test
    void alsoCatchesSynchronousInitializationAndIncludesSuppressedReason() {
        var failure = new IllegalArgumentException("Invalid MCP protocol");
        failure.addSuppressed(new IllegalStateException("HTTP 404 Not Found"));
        var diagnostic = assertThrows(McpToolLoadingFailure.class, () -> DiagnosticMcpToolset.diagnose(
                () -> { throw failure; }, "travel-mcp", "https://travel.example/mcp").blockingFirst());
        assertTrue(diagnostic.getMessage().contains("server=travel-mcp"));
        assertTrue(diagnostic.getMessage().contains("Invalid MCP protocol"));
        assertTrue(diagnostic.getMessage().contains("HTTP 404 Not Found"));
    }

    @Test
    void boundsCyclicCausesAndRemovesLogInjectionAndBasicCredentials() {
        var first = new RuntimeException("Bad response\nforged-log Authorization: Basic dXNlcjpwYXNz");
        var second = new RuntimeException("Connection failed", first);
        first.initCause(second);
        var diagnostic = new McpToolLoadingFailure("travel-mcp", "invalid url", first).getMessage();
        assertFalse(diagnostic.contains("\n"));
        assertFalse(diagnostic.contains("dXNlcjpwYXNz"));
        assertTrue(diagnostic.length() < 5000);
    }

    @Test
    void successfulLoadIsUnchanged() {
        assertTrue(DiagnosticMcpToolset.diagnose(Flowable::<BaseTool>empty,
                "travel-mcp", "https://travel.example/mcp").toList().blockingGet().isEmpty());
    }
}
