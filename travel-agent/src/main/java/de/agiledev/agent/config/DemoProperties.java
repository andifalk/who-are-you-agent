package de.agiledev.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo")
public record DemoProperties(
        String model, String travelMcpUrl, String expenseMcpUrl,
        String corporateIssuer, String travelIssuer,
        String corporateClientId, String corporateClientSecret,
        String travelGrantClientId, String travelGrantClientSecret,
        String insecureClientId, String insecureClientSecret,
        Target travel, Target expense, String exchangeScope, String exchangeAudience,
        String inspectorUrl, String inspectorApiKey, AgentIdentity agentIdentity) {
    public record Target(String audience, String scopes) {
    }

    public record AgentIdentity(boolean enabled, String verifiedProxyHeader) {
    }
}
