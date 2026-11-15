package com.example.expense.inspector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** Best-effort audit of authorization decisions; never sends credentials or tool arguments. */
@Component
public class ToolDecisionPublisher {
    private static final Logger log = LoggerFactory.getLogger(ToolDecisionPublisher.class);
    private final ObjectMapper mapper;
    private final String url;
    private final String key;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ToolDecisionPublisher(ObjectMapper mapper,
                                 @Value("${inspector.url:}") String url,
                                 @Value("${inspector.api-key:}") String key) {
        this.mapper = mapper;
        this.url = url;
        this.key = key;
    }

    public void publish(Authentication auth, String tool, boolean allowed, String reason) {
        if (url.isBlank()) return;
        String id = correlationId();
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("correlationId", id);
            event.put("phase", "TOOL_AUTHORIZATION");
            event.put("tool", tool);
            event.put("decision", allowed ? "ALLOW" : "DENY");
            event.put("human", auth == null ? "" : auth.getName());
            event.put("agent", "");
            event.put("issuer", "");
            event.put("audience", "");
            event.put("scopes", auth == null ? "" : auth.getAuthorities().stream()
                    .map(a -> a.getAuthority()).filter(a -> a.startsWith("SCOPE_"))
                    .map(a -> a.substring(6)).sorted().collect(Collectors.joining(" ")));
            if (auth instanceof JwtAuthenticationToken jwt) {
                var token = jwt.getToken();
                // Subject may identify a human or service account. azp identifies the OAuth client.
                event.put("human", Objects.toString(token.getSubject(), ""));
                Object azp = token.getClaims().get("azp");
                event.put("agent", azp instanceof String ? azp : "");
                event.put("issuer", Objects.toString(token.getClaims().get("iss"), ""));
                event.put("audience", String.join(" ", token.getAudience()));
            }
            event.put("reason", reason);
            var request = HttpRequest.newBuilder(URI.create(url.replaceAll("/$", "") + "/api/events"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                    .header("X-Inspector-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event))).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
                if (failure != null) log.warn("Inspector delivery failed correlationId={} tool={}", id, tool);
                else if (response.statusCode() / 100 != 2)
                    log.warn("Inspector rejected event correlationId={} tool={} httpStatus={}", id, tool, response.statusCode());
            });
        } catch (Exception failure) {
            log.warn("Inspector event unavailable correlationId={} tool={} failureType={}", id, tool, failure.getClass().getName());
        }
    }

    private String correlationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            String supplied = request.getHeader("X-Correlation-ID");
            if (supplied != null && supplied.matches("[A-Za-z0-9_-]{1,128}")) return supplied;
            String attribute = ToolDecisionPublisher.class.getName() + ".correlationId";
            if (request.getAttribute(attribute) instanceof String existing) return existing;
            String generated = UUID.randomUUID().toString();
            request.setAttribute(attribute, generated);
            return generated;
        }
        return UUID.randomUUID().toString();
    }
}
