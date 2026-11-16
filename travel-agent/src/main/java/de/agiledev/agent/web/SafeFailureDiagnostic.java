package de.agiledev.agent.web;

import com.google.genai.errors.ApiException;
import de.agiledev.agent.diagnostics.McpToolLoadingFailure;

import java.util.Collections;
import java.util.IdentityHashMap;
import static de.agiledev.agent.diagnostics.SecretRedactor.sanitize;

/** Includes sanitized Gemini messages; other exception messages remain excluded. */
final class SafeFailureDiagnostic {
    private SafeFailureDiagnostic() { }

    static String describe(Throwable failure) {
        return describe(failure, System.getenv("GOOGLE_API_KEY"), System.getenv("GEMINI_API_KEY"));
    }

    static String describe(Throwable failure, String... secrets) {
        var mcp = McpToolLoadingFailure.find(failure);
        if (mcp != null) return sanitize(mcp.getMessage(), secrets);
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

}
