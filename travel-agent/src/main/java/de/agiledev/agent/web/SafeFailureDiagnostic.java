package de.agiledev.agent.web;

import com.google.genai.errors.ApiException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

/** Includes sanitized Gemini messages; other exception messages remain excluded. */
final class SafeFailureDiagnostic {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)([\\\"']?(?:x-goog-api-key|google_api_key|gemini_api_key|api[_-]?key|key|access_token|refresh_token|id_token|client_secret|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s\\\"'&,}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[^\\s\\\"'&,}]+");
    private static final Pattern GOOGLE_KEY = Pattern.compile("AIza[A-Za-z0-9_-]+");

    private SafeFailureDiagnostic() { }

    static String describe(Throwable failure) {
        return describe(failure, System.getenv("GOOGLE_API_KEY"), System.getenv("GEMINI_API_KEY"));
    }

    static String describe(Throwable failure, String... secrets) {
        var result = new StringBuilder();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.size() < 12 && seen.add(cause);
             cause = cause.getCause()) {
            if (!result.isEmpty()) result.append("; caused by ");
            result.append(cause.getClass().getName());
            if (cause instanceof ApiException api) {
                result.append(" Gemini HTTP ").append(api.code())
                        .append(" message=").append(sanitize(api.message(), secrets));
            }
            for (var frame : cause.getStackTrace()) {
                result.append("\n  at ").append(frame);
            }
        }
        return result.toString();
    }

    private static String sanitize(String message, String... secrets) {
        if (message == null) return "<none>";
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                message = message.replace(secret, "[REDACTED]");
                message = message.replace(java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8), "[REDACTED]");
            }
        }
        message = BEARER.matcher(message).replaceAll("Bearer [REDACTED]");
        message = CREDENTIAL.matcher(message).replaceAll("$1[REDACTED]");
        message = GOOGLE_KEY.matcher(message).replaceAll("[REDACTED]");
        message = message.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        return message.length() > 4096 ? message.substring(0, 4096) + " [truncated]" : message;
    }
}
