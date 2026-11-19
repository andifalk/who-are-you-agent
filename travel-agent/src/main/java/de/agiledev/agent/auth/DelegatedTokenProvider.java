package de.agiledev.agent.auth;

import de.agiledev.agent.web.UserDisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(DelegatedTokenProvider.class);
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
            throw new TokenAcquisitionException("AGENT_IDENTITY", "not implemented", "Select Overprivileged or Delegated");
        return delegated(resource, user, request, correlationId);
    }

    private String delegated(Resource resource, Authentication user, HttpServletRequest request, String id) {
        org.springframework.security.oauth2.client.OAuth2AuthorizedClient authorized;
        try {
            authorized = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("corporate")
                .principal(user).attribute(HttpServletRequest.class.getName(), request).build());
        } catch (org.springframework.security.oauth2.core.OAuth2AuthorizationException e) {
            throw oauthFailure("CORPORATE_LOGIN", 0, e.getError().getErrorCode());
        } catch (RuntimeException e) {
            throw new TokenAcquisitionException("CORPORATE_LOGIN", "Corporate token unavailable",
                    "Check OIDC configuration and connectivity; sign in again");
        }
        if (authorized == null || authorized.getAccessToken() == null)
            throw new TokenAcquisitionException("CORPORATE_LOGIN", "no access token", "Sign in again");
        String token1 = authorized.getAccessToken().getTokenValue();
        String fingerprint = Integer.toHexString(token1.hashCode()); // cache key only; never logged
        log.info("Corporate token issued with scopes [{}]", authorized.getAccessToken().getScopes());
        inspector.publish("TOKEN1_ISSUED", "", "", UserDisplayName.of(user), "travel-agent", "Corporate token available (possibly refreshed)", id);
        String key = "token3." + resource;
        HttpSession session = request.getSession();
        Cached cache = (Cached) session.getAttribute(key);
        if (cache != null && cache.valid(fingerprint)) {
            reportTokenClaims(DemoMode.DELEGATED, resource, cache.token(), id, true, UserDisplayName.of(user));
            return cache.token();
        }
        var target = resource == Resource.TRAVEL ? cfg.travel() : cfg.expense();
        log.info("Exchange Corporate token for target {} and exchange scope {}", target, cfg.exchangeScope());
        JsonNode exchanged = post("TOKEN_EXCHANGE", cfg.corporateIssuer() + "/protocol/openid-connect/token",
                Map.of("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                        "client_id", cfg.corporateClientId(), "client_secret", cfg.corporateClientSecret(),
                        "subject_token", token1, "subject_token_type", "urn:ietf:params:oauth:token-type:access_token",
                        "scope", cfg.exchangeScope(), "audience", cfg.exchangeAudience()));
        String assertion = required(exchanged, "access_token", "TOKEN_EXCHANGE");
        log.info("JWT assertion for travel realm issued with scopes [{}]", exchanged.get("scope"));
        inspector.publish("TOKEN_EXCHANGED", "", "", UserDisplayName.of(user), "travel-agent", "JWT assertion for travel realm", id);
        Map<String, String> grant = new LinkedHashMap<>();
        grant.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        grant.put("assertion", assertion);
        grant.put("client_id", cfg.travelGrantClientId());
        grant.put("client_secret", cfg.travelGrantClientSecret());
        grant.put("scope", target.scopes());
        log.info("Redeem access token from JWT assertion for travel realm");
        // Audience is enforced via Keycloak client scope mapper; verify aud in MCP resource server.
        JsonNode redeemed = post("JWT_BEARER_GRANT", cfg.travelIssuer() + "/protocol/openid-connect/token", grant);
        String token3 = required(redeemed, "access_token", "JWT_BEARER_GRANT");
        reportTokenClaims(DemoMode.DELEGATED, resource, token3, id, false, UserDisplayName.of(user));
        long expires = redeemed.path("expires_in").asLong(60);
        session.setAttribute(key, new Cached(token3, Instant.now().plusSeconds(expires), fingerprint));
        inspector.publish("JWT_GRANT_REDEEMED", "", "", UserDisplayName.of(user), "travel-agent",
                "Travel token issued for " + resource + " (audience must be verified by resource server)", id);
        return token3;
    }

    private void reportTokenClaims(DemoMode mode, Resource resource, String token, String id, boolean cached, String human) {
        // Diagnostic decoding only: signature/issuer/audience validation remains with the MCP servers.
        var claims = TokenClaimDiagnostic.read(json, token, cfg.corporateClientSecret(),
                cfg.travelGrantClientSecret(), cfg.insecureClientSecret(), cfg.inspectorApiKey(),
                System.getenv("GOOGLE_API_KEY"), System.getenv("GEMINI_API_KEY"));
        log.info("{} token claims correlationId={} resource={} source={} signatureVerified=false {}",
                mode == DemoMode.DELEGATED ? "Delegated" : "Overprivileged", id, resource, cached ? "cache" : "issued", claims.summary());
        inspector.publishTokenClaims(mode, resource.name(), human, cached ? "cache" : "issued",
                claims.available(), claims.issuer(), claims.audience(), claims.scopes(), id);
    }

    private String serviceToken(Resource resource, HttpSession session, String id) {
        String key = "service.token." + resource;
        Cached cache = (Cached) session.getAttribute(key);
        if (cache != null && cache.valid("service")) {
            reportTokenClaims(DemoMode.OVERPRIVILEGED, resource, cache.token(), id, true, "service-account");
            return cache.token();
        }
        // Demo 1 intentionally uses an overprivileged service identity, but JWT validation stays active.
        JsonNode response = post("CLIENT_CREDENTIALS", cfg.travelIssuer() + "/protocol/openid-connect/token",
                Map.of("grant_type", "client_credentials", "client_id", cfg.insecureClientId(),
                        "client_secret", cfg.insecureClientSecret()));
        String token = required(response, "access_token", "CLIENT_CREDENTIALS");
        reportTokenClaims(DemoMode.OVERPRIVILEGED, resource, token, id, false, "service-account");
        session.setAttribute(key, new Cached(token, Instant.now().plusSeconds(response.path("expires_in").asLong(60)), "service"));
        inspector.publish("TOKEN1_ISSUED", "", "", "service-account", "travel-agent",
                "Overprivileged service credential (Demo 1)", id);
        return token;
    }

    static String required(JsonNode node, String field, String step) {
        var valueNode = node == null ? null : node.get(field);
        String value = valueNode != null && valueNode.isString() ? valueNode.asString() : null;
        if (value == null || value.isBlank())
            throw new TokenAcquisitionException(step, "invalid token response",
                    "Expected access token is missing; check Keycloak configuration");
        return value;
    }

    private JsonNode post(String step, String url, Map<String, String> form) {
        try {
            StringJoiner body = new StringJoiner("&");
            form.forEach((k, v) -> body.add(encode(k) + "=" + encode(v)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return parseResponse(step, response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenAcquisitionException(step, "request interrupted", "Retry the request");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new TokenAcquisitionException(step, "request timed out", "Check Keycloak connectivity");
        } catch (java.io.IOException e) {
            throw new TokenAcquisitionException(step, "connection failed",
                    "Check Keycloak DNS, TLS certificates, and network connectivity");
        } catch (IllegalArgumentException e) {
            throw new TokenAcquisitionException(step, "invalid endpoint configuration",
                    "Check CORPORATE_ISSUER and TRAVEL_ISSUER");
        }
    }

    JsonNode parseResponse(String step, int status, String body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (tools.jackson.core.JacksonException e) {
            if (status != 200) throw oauthFailure(step, status, "unknown");
            throw new TokenAcquisitionException(step, "invalid JSON response",
                    "Check the token endpoint and upstream proxy");
        }
        if (status != 200) {
            var error = node == null ? null : node.get("error");
            throw oauthFailure(step, status, error != null && error.isString() ? error.asString() : "unknown");
        }
        required(node, "access_token", step);
        return node;
    }

    static TokenAcquisitionException oauthFailure(String step, int status, String error) {
        // Only known OAuth codes are included. Descriptions can echo submitted credentials.
        String code = switch (error == null ? "" : error) {
            case "invalid_client", "unauthorized_client", "invalid_grant", "invalid_scope",
                 "invalid_target", "access_denied", "unsupported_grant_type", "invalid_request" -> error;
            default -> "unknown_error";
        };
        String hint = switch (code) {
            case "invalid_client" -> "Check client ID, client secret, and realm";
            case "unauthorized_client", "unsupported_grant_type" -> "Check permission for the grant type; enable Service Accounts for CLIENT_CREDENTIALS";
            case "invalid_grant" -> "Token/assertion expired or rejected; sign in again and check issuer/trust configuration";
            case "invalid_scope" -> "Check requested scopes and client scope assignments";
            case "invalid_target" -> "Check the exchange audience and allowed target clients";
            case "access_denied" -> "Check Keycloak policies and Token Exchange permissions";
            case "invalid_request" -> "Check grant parameters and Keycloak grant configuration";
            default -> status == 404 ? "Check the realm and token endpoint path"
                    : status >= 500 ? "Keycloak or upstream proxy unavailable; check server logs"
                    : "Check Keycloak configuration and server logs";
        };
        return new TokenAcquisitionException(step,
                (status > 0 ? "HTTP " + status + " " : "") + code, hint);
    }

    public enum Resource {TRAVEL, EXPENSE}

    private record Cached(String token, Instant expiresAt, String token1Fingerprint) {
        boolean valid(String fingerprint) {
            return token1Fingerprint.equals(fingerprint) && Instant.now().isBefore(expiresAt.minusSeconds(45));
        }
    }
}
