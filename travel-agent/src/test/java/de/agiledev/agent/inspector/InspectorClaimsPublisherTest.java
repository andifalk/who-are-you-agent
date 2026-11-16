package de.agiledev.agent.inspector;

import com.sun.net.httpserver.HttpServer;
import de.agiledev.agent.config.DemoProperties;
import de.agiledev.agent.auth.DemoMode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InspectorClaimsPublisherTest {
    @Test
    void transmitsDistinctTravelAndExpenseClaimsUsingExistingEventSchema() throws Exception {
        var bodies = new LinkedBlockingQueue<String>();
        var keys = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/events", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("X-Inspector-Key"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var config = mapper.readValue("{\"inspectorUrl\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "\",\"inspectorApiKey\":\"inspector-secret\"}", DemoProperties.class);
            var publisher = new InspectorPublisher(config, mapper);
            for (DemoMode mode : new DemoMode[]{DemoMode.DELEGATED, DemoMode.OVERPRIVILEGED}) {
                for (String resource : new String[]{"TRAVEL", "EXPENSE"}) {
                    String audience = mode == DemoMode.OVERPRIVILEGED ? "[\"travel-mcp\", \"expense-mcp\"]"
                            : resource.equals("TRAVEL") ? "travel-mcp" : "expense-mcp";
                    String scopes = mode == DemoMode.OVERPRIVILEGED
                            ? "travel.read travel.book expenses.create expenses.read expenses.approve"
                            : resource.equals("TRAVEL") ? "travel.read travel.book" : "expenses.create expenses.read";
                    String source = resource.equals("TRAVEL") ? "issued" : "cache";
                    publisher.publishTokenClaims(mode, resource, mode == DemoMode.DELEGATED ? "alice" : "service-account", source, true,
                            "https://issuer.example/realms/travel", audience, scopes, "request-123");
                    String body = bodies.poll(5, TimeUnit.SECONDS);
                    assertNotNull(body);
                    var event = mapper.readTree(body);
                    assertEquals(mode.name() + "_TOKEN_CLAIMS_" + resource, event.path("phase").asString());
                    assertEquals("request-123", event.path("correlationId").asString());
                    assertTrue(event.path("reason").asString().contains("mode=" + mode.name()));
                    assertEquals(mode == DemoMode.DELEGATED ? "alice" : "service-account", event.path("human").asString());
                    assertEquals("https://issuer.example/realms/travel", event.path("issuer").asString());
                    assertEquals(audience, event.path("audience").asString());
                    assertEquals(scopes, event.path("scopes").asString());
                    assertTrue(event.path("reason").asString().contains("source=" + source));
                    assertTrue(event.path("reason").asString().contains("signatureVerified=false"));
                    assertEquals("inspector-secret", keys.poll(5, TimeUnit.SECONDS));
                    assertFalse(body.contains("inspector-secret"));
                    assertFalse(event.has("access_token"));
                    assertFalse(event.has("decision"));
                }
            }
            publisher.publishTokenClaims(DemoMode.DELEGATED, "EXPENSE", "alice", "issued", false, "", "", "", "request-456");
            String unavailable = bodies.poll(5, TimeUnit.SECONDS);
            assertNotNull(unavailable);
            assertTrue(unavailable.contains("claims=unavailable"));
        } finally {
            server.stop(0);
        }
    }
}
