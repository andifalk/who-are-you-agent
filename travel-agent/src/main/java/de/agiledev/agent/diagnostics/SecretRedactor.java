package de.agiledev.agent.diagnostics;

import java.util.regex.Pattern;

public final class SecretRedactor {
    private SecretRedactor() { }
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)([\\\"']?(?:x-goog-api-key|google_api_key|gemini_api_key|api[_-]?key|key|access_token|refresh_token|id_token|client_secret|subject_token|assertion|password|token|x-inspector-key|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s\\\"'&,}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(?:Bearer|Basic)\\s+[^\\s\\\"'&,}]+");
    private static final Pattern GOOGLE_KEY = Pattern.compile("AIza[A-Za-z0-9_-]+");

    public static String sanitize(String message, String... secrets) {
        if (message == null) return "<none>";
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                message = message.replace(secret, "[REDACTED]");
                message = message.replace(java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8), "[REDACTED]");
            }
        }
        message = message.replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED JWT]");
        message = BEARER.matcher(message).replaceAll("Bearer [REDACTED]");
        message = message.replaceAll("(?i)(https?://)[^/\\s@]+@", "$1[REDACTED]@");
        message = CREDENTIAL.matcher(message).replaceAll("$1[REDACTED]");
        message = GOOGLE_KEY.matcher(message).replaceAll("[REDACTED]");
        message = message.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        return message.length() > 4096 ? message.substring(0, 4096) + " [truncated]" : message;
    }
}
