package de.agiledev.agent.inspector;

import tools.jackson.databind.ObjectMapper;
import de.agiledev.agent.config.DemoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class InspectorPublisher {
    private static final Logger log = LoggerFactory.getLogger(InspectorPublisher.class);
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
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
                if (failure != null) {
                    log.warn("Inspector event delivery failed correlationId={} phase={} failureType={}",
                            correlationId, phase, failure.getClass().getName());
                } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("Inspector event rejected correlationId={} phase={} httpStatus={}",
                            correlationId, phase, response.statusCode());
                }
            });
        } catch (Exception failure) {
            // Telemetry must never break authorization. Do not log credentials or payloads.
            log.warn("Inspector event preparation failed correlationId={} phase={} failureType={}",
                    correlationId, phase, failure.getClass().getName());
        }
    }
}
