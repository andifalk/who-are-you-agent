package de.agiledev.agent.diagnostics;

import java.net.URI;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Safe, compact diagnostic; deliberately does not retain the raw transport exception. */
public final class McpToolLoadingFailure extends RuntimeException {
    public static McpToolLoadingFailure find(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (failure != null && seen.size() < 12 && seen.add(failure)) {
            if (failure instanceof McpToolLoadingFailure mcp) return mcp;
            failure = failure.getCause();
        }
        return null;
    }

    public McpToolLoadingFailure(String server, String url, Throwable failure, String... secrets) {
        super(SecretRedactor.sanitize("MCP server=" + server + " endpoint=" + endpoint(url)
                + " operation=initialize/list_tools failed: " + reasons(failure, secrets), secrets));
    }

    static String endpoint(String url) {
        try {
            var uri = URI.create(url);
            // Never expose URL credentials, query parameters or fragments.
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception ignored) {
            return "<invalid endpoint>";
        }
    }

    private static String reasons(Throwable failure, String... secrets) {
        var result = new StringBuilder();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        append(result, failure, seen, secrets);
        return SecretRedactor.sanitize(result.toString(), secrets);
    }

    private static void append(StringBuilder result, Throwable failure,
                               java.util.Set<Throwable> seen, String[] secrets) {
        if (failure == null || seen.size() >= 12 || !seen.add(failure)) return;
        if (!result.isEmpty()) result.append(" -> ");
        result.append(failure.getClass().getSimpleName()).append(": ")
                .append(SecretRedactor.sanitize(failure.getMessage(), secrets));
        append(result, failure.getCause(), seen, secrets);
        for (Throwable suppressed : failure.getSuppressed()) append(result, suppressed, seen, secrets);
    }
}
