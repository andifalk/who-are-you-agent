package com.example.identityinspector.event;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record InspectorEvent(
        @NotBlank String correlationId,
        @NotBlank String phase,
        @NotBlank String tool,
        @NotBlank String decision,
        String human, String agent, String issuer, String audience,
        String scopes, String spiffeId, String reason,
        Instant timestamp) {
    public InspectorEvent withTimestamp() {
        return new InspectorEvent(correlationId, phase, tool, decision, human, agent, issuer, audience, scopes, spiffeId, reason, timestamp == null ? Instant.now() : timestamp);
    }
}
