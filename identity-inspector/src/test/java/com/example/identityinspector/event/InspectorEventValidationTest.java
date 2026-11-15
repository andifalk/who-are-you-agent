package com.example.identityinspector.event;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InspectorEventValidationTest {
    @Test
    void acceptsAgentLifecycleEventsWithoutToolOrDecision() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var event = new InspectorEvent("request-1", "TOKEN1_ISSUED", "", "",
                    "alice", "travel-agent", null, null, null, null, "Token issued", null);
            assertTrue(factory.getValidator().validate(event).isEmpty());
        }
    }

    @Test
    void stillRequiresCorrelationIdAndPhase() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var event = new InspectorEvent("", "", "", "",
                    null, null, null, null, null, null, null, null);
            var violations = factory.getValidator().validate(event);
            assertEquals(2, violations.size());
        }
    }
}
