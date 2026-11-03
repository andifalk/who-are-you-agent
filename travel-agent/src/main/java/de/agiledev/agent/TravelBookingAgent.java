package de.agiledev.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import de.agiledev.agent.config.DemoProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class TravelBookingAgent {
    private final DemoProperties cfg;

    public TravelBookingAgent(DemoProperties cfg) {
        this.cfg = cfg;
    }

    public Answer run(String prompt, String travelToken, String expenseToken, String correlationId) {
        // Important: construct BOTH clients and BOTH bearer headers for THIS invocation only.
        // They are never Spring singleton fields, never stored in a global ADK agent.
        var travelParams = StreamableHttpServerParameters.builder()
                .url(cfg.travelMcpUrl()).headers(Map.of("Authorization", "Bearer " + travelToken))
                .timeout(Duration.ofSeconds(20)).readTimeout(Duration.ofSeconds(90)).build();
        var expenseParams = StreamableHttpServerParameters.builder()
                .url(cfg.expenseMcpUrl()).headers(Map.of("Authorization", "Bearer " + expenseToken))
                .timeout(Duration.ofSeconds(20)).readTimeout(Duration.ofSeconds(90)).build();
        try (var travel = new McpToolset(travelParams); var expense = new McpToolset(expenseParams)) {
            var agent = LlmAgent.builder()
                    .name("travel_booking_agent")
                    .description("Corporate travel and expenses assistant")
                    .model(cfg.model())
                    .instruction("""
                            You are a corporate travel assistant operating exclusively on deterministic DEMO data.
                            Available tools on the Travel MCP: search_flights(origin,destination,date),
                            book_flight(flightId,traveler,date), get_booking(bookingId).
                            Available tools on the Expense MCP: create_expense(bookingId,amount),
                            get_expense(expenseId), approve_expense(expenseId).
                            FL-100 is the deterministic flight; BOOK-100 is the deterministic booking;
                            EXP-8000 is a separate, pre-seeded 8000 EUR expense, not the flight expense.
                            Never automatically approve an expense. Call approve_expense ONLY when
                            the current user explicitly requests approval of a specific expense.
                            Never claim an approval succeeded unless the tool returned success.
                            Explain authorization failures clearly; do not retry with other credentials.
                            The server-side authorization check, not this instruction, is the security boundary.
                            Do not fabricate flight prices, booking IDs, tool results or permissions.
                            """)
                    .tools(travel, expense).build();
            // A runner and session are per request: isolation is prioritized over conversation memory.
            var runner = new InMemoryRunner(agent);
            var session = runner.sessionService().createSession(runner.appName(), correlationId).blockingGet();
            var answer = new StringBuilder();
            runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(prompt)),
                    RunConfig.builder().build()).blockingForEach((Event event) -> {
                if (event.finalResponse()) answer.append(event.stringifyContent());
            });
            return new Answer(answer.toString(), correlationId);
        }
    }

    public record Answer(String text, String correlationId) {
    }
}
