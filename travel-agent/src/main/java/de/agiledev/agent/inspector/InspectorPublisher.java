package de.agiledev.agent.inspector;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.agiledev.agent.config.DemoProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class InspectorPublisher {
    private final DemoProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public InspectorPublisher(DemoProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public void publish(String phase, String tool, String decision, String human, String agent, String reason, String correlationId) {
        if (props.inspectorUrl() == null || props.inspectorUrl().isBlank()) return;
        try {
            var body = mapper.writeValueAsString(Map.of("phase", phase, "tool", tool == null ? "" : tool, "decision", decision == null ? "" : decision,
                    "human", human == null ? "" : human, "agent", agent == null ? "" : agent, "reason", reason == null ? "" : reason, "correlationId", correlationId));
            var req = HttpRequest.newBuilder(URI.create(props.inspectorUrl().replaceAll("/$", "") + "/api/events"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json")
                    .header("X-Inspector-Key", props.inspectorApiKey() == null ? "" : props.inspectorApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
        } catch (Exception ignored) { /* telemetry must never break authorization */ }
    }
}
