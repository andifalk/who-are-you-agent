package de.agiledev.agent.auth;

/** Contains only application-generated diagnostics, never provider response bodies. */
public final class TokenAcquisitionException extends RuntimeException {
    public TokenAcquisitionException(String step, String problem, String hint) {
        super("Keycloak " + step + ": " + problem + ". " + hint);
    }
}
