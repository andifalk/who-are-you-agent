package com.example.expense.inspector;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolDecisionPublisherTest {
    @Test
    void sendsDecisionAndCorrelationWithoutBearerToken() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var headers = new LinkedBlockingQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/events", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("X-Inspector-Key"));
            received.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            var request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-ID", "request-123");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var jwt = Jwt.withTokenValue("secret-bearer-token").header("alg", "RS256")
                    .subject("user-uuid").claim("given_name", "Alice").claim("family_name", "Example").issuer("https://issuer.example").audience(List.of("expense-mcp"))
                    .claim("azp", "agent-client").build();
            var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_expense.read")));
            var mapper = new ObjectMapper();
            var publisher = new ToolDecisionPublisher(mapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "inspector-secret");
            publisher.publish(auth, "test_tool", false, "Missing scope");
            String body = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(body);
            var event = mapper.readTree(body);
            assertEquals("DENY", event.path("decision").asString());
            assertEquals("test_tool", event.path("tool").asString());
            assertEquals("request-123", event.path("correlationId").asString());
            assertEquals("Alice Example", event.path("human").asString());
            assertEquals("user-uuid", auth.getName());
            assertEquals("user-uuid", auth.getToken().getSubject());
            assertEquals("agent-client", event.path("agent").asString());
            assertEquals("expense.read", event.path("scopes").asString());
            assertEquals("inspector-secret", headers.poll(5, TimeUnit.SECONDS));
            assertFalse(body.contains("secret-bearer-token"));
            assertFalse(body.contains("inspector-secret"));
            assertDoesNotThrow(() -> new ToolDecisionPublisher(mapper, "invalid url", "secret")
                    .publish(auth, "test_tool", true, "Scope present"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            server.stop(0);
        }
    }
}
