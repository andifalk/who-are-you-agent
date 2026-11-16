package de.agiledev.agent.auth;

import de.agiledev.agent.diagnostics.SecretRedactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.StringJoiner;

/** Allowlisted JWT metadata for diagnostics, not token validation. */
final class TokenClaimDiagnostic {
    private TokenClaimDiagnostic() { }

    static String describe(ObjectMapper mapper, String token, String... secrets) {
        return read(mapper, token, secrets).summary();
    }

    static Snapshot read(ObjectMapper mapper, String token, String... secrets) {
        try {
            if (token == null || token.length() > 131072) return Snapshot.unavailable();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return Snapshot.unavailable();
            var claims = mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (claims == null || !claims.isObject()) return Snapshot.unavailable();
            // Do not serialize the payload: it can contain arbitrary sensitive claims.
            String summary = "iss=" + text(claims.get("iss"))
                    + " aud=" + audience(claims.get("aud")) + " scope=" + text(claims.get("scope"));
            String[] sensitive = new String[secrets.length + 1];
            System.arraycopy(secrets, 0, sensitive, 0, secrets.length);
            sensitive[secrets.length] = token;
            return new Snapshot(
                    SecretRedactor.sanitize(plainText(claims.get("iss")), sensitive),
                    SecretRedactor.sanitize(claims.path("aud").isArray()
                            ? audience(claims.get("aud")) : plainText(claims.get("aud")), sensitive),
                    SecretRedactor.sanitize(plainText(claims.get("scope")), sensitive),
                    SecretRedactor.sanitize(summary, sensitive), true);
        } catch (Exception ignored) {
            // Never log parsing errors: their messages can include the input token or payload.
            return Snapshot.unavailable();
        }
    }

    record Snapshot(String issuer, String audience, String scopes, String summary, boolean available) {
        static Snapshot unavailable() {
            return new Snapshot("", "", "", "claims=unavailable", false);
        }
    }

    private static String plainText(JsonNode value) {
        return value == null || value.isNull() ? "<missing>"
                : value.isString() ? value.asString() : "<invalid type>";
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? "<missing>"
                : value.isString() ? '"' + value.asString() + '"' : "<invalid type>";
    }

    private static String audience(JsonNode value) {
        if (value == null || !value.isArray()) return text(value);
        var values = new StringJoiner(", ", "[", "]");
        for (var item : value) values.add(text(item));
        return values.toString();
    }
}
