package de.agiledev.agent.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.agiledev.agent.config.DemoProperties;
import de.agiledev.agent.inspector.InspectorPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class DelegatedTokenProvider {
    private final OAuth2AuthorizedClientManager manager;
    private final DemoProperties cfg;
    private final InspectorPublisher inspector;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public DelegatedTokenProvider(OAuth2AuthorizedClientManager manager, DemoProperties cfg, InspectorPublisher inspector, ObjectMapper json) {
        this.manager = manager;
        this.cfg = cfg;
        this.inspector = inspector;
        this.json = json;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public String tokenFor(Resource resource, DemoMode mode, Authentication user, HttpServletRequest request, String correlationId) {
        if (mode == DemoMode.OVERPRIVILEGED) return serviceToken(resource, request.getSession(), correlationId);
        if (mode == DemoMode.GOOGLE_AGENT_IDENTITY)
            throw new IllegalStateException("Google Agent Identity erfordert eine verifizierte Google-Agent-Credential-Integration; nicht implementiert: fail closed");
        return delegated(resource, user, request, correlationId);
    }

    private String delegated(Resource resource, Authentication user, HttpServletRequest request, String id) {
        var authorized = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("corporate")
                .principal(user).attribute(HttpServletRequest.class.getName(), request).build());
        if (authorized == null) throw new IllegalStateException("Alice muss sich erneut anmelden");
        String token1 = authorized.getAccessToken().getTokenValue();
        String fingerprint = Integer.toHexString(token1.hashCode()); // cache key only; never logged
        inspector.publish("TOKEN1_ISSUED", "", "", user.getName(), "travel-agent", "Corporate token available (possibly refreshed)", id);
        String key = "token3." + resource;
        HttpSession session = request.getSession();
        Cached cache = (Cached) session.getAttribute(key);
        if (cache != null && cache.valid(fingerprint)) return cache.token();
        var target = resource == Resource.TRAVEL ? cfg.travel() : cfg.expense();
        JsonNode exchanged = post(cfg.corporateIssuer() + "/protocol/openid-connect/token",
                Map.of("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                        "client_id", cfg.corporateClientId(), "client_secret", cfg.corporateClientSecret(),
                        "subject_token", token1, "subject_token_type", "urn:ietf:params:oauth:token-type:access_token",
                        "scope", cfg.exchangeScope(), "audience", cfg.exchangeAudience()));
        String assertion = required(exchanged, "access_token");
        inspector.publish("TOKEN_EXCHANGED", "", "", user.getName(), "travel-agent", "JWT assertion for travel realm", id);
        Map<String, String> grant = new LinkedHashMap<>();
        grant.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        grant.put("assertion", assertion);
        grant.put("client_id", cfg.travelGrantClientId());
        grant.put("client_secret", cfg.travelGrantClientSecret());
        grant.put("scope", target.scopes());
        // Audience is enforced via Keycloak client scope mapper; verify aud in MCP resource server.
        JsonNode redeemed = post(cfg.travelIssuer() + "/protocol/openid-connect/token", grant);
        String token3 = required(redeemed, "access_token");
        long expires = redeemed.path("expires_in").asLong(60);
        session.setAttribute(key, new Cached(token3, Instant.now().plusSeconds(expires), fingerprint));
        inspector.publish("JWT_GRANT_REDEEMED", "", "", user.getName(), "travel-agent",
                "Travel token issued for " + resource + " (audience must be verified by resource server)", id);
        return token3;
    }

    private String serviceToken(Resource resource, HttpSession session, String id) {
        String key = "service.token." + resource;
        Cached cache = (Cached) session.getAttribute(key);
        if (cache != null && cache.valid("service")) return cache.token();
        // Demo 1 intentionally uses an overprivileged service identity, but JWT validation stays active.
        JsonNode response = post(cfg.travelIssuer() + "/protocol/openid-connect/token",
                Map.of("grant_type", "client_credentials", "client_id", cfg.insecureClientId(),
                        "client_secret", cfg.insecureClientSecret()));
        String token = required(response, "access_token");
        session.setAttribute(key, new Cached(token, Instant.now().plusSeconds(response.path("expires_in").asLong(60)), "service"));
        inspector.publish("TOKEN1_ISSUED", "", "", "service-account", "travel-agent",
                "Overprivileged service credential (Demo 1)", id);
        return token;
    }

    private String required(JsonNode node, String field) {
        String value = node.path(field).asString();
        if (value == null || value.isBlank()) throw new IllegalStateException("Token endpoint missing " + field);
        return value;
    }

    private JsonNode post(String url, Map<String, String> form) {
        try {
            StringJoiner body = new StringJoiner("&");
            form.forEach((k, v) -> body.add(encode(k) + "=" + encode(v)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Keycloak token endpoint HTTP " + response.statusCode() + " (details redacted)");
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted token request", e);
        } catch (java.io.IOException | tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Token endpoint unavailable", e);
        }
    }

    public enum Resource {TRAVEL, EXPENSE}

    private record Cached(String token, Instant expiresAt, String token1Fingerprint) {
        boolean valid(String fingerprint) {
            return token1Fingerprint.equals(fingerprint) && Instant.now().isBefore(expiresAt.minusSeconds(45));
        }
    }
}
