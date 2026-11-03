package com.example.identityinspector.controller;

import com.example.identityinspector.event.EventHub;
import com.example.identityinspector.event.InspectorEvent;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
public class InspectorController {
    private final EventHub hub;
    private final boolean demoEnabled;

    public InspectorController(EventHub hub, @Value("${inspector.demo-enabled:true}") boolean demoEnabled) {
        this.hub = hub;
        this.demoEnabled = demoEnabled;
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return hub.subscribe();
    }

    @GetMapping("/history")
    public List<InspectorEvent> history() {
        return hub.history();
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@Valid @RequestBody InspectorEvent event) {
        hub.publish(event);
    }

    @PostMapping("/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        hub.clear();
    }

    @PostMapping("/demo/{mode}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void demo(@PathVariable String mode) {
        if (!demoEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!List.of("insecure", "delegated", "agent-identity").contains(mode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown mode");
        var scope = mode.equals("insecure") ? "travel.read travel.book expenses.create expenses.approve" : "travel.read travel.book expenses.create expenses.read";
        var agent = mode.equals("agent-identity") ? "principal://agents.google.example/travel-agent" : "travel-agent";
        var spiffe = mode.equals("agent-identity") ? "spiffe://example.org/travel-agent" : null;
        hub.publish(new InspectorEvent("demo-001", mode, "search_flights", "ALLOW", "alice", agent, "corporate", "travel-mcp", scope, spiffe, "Flights found", null));
        hub.publish(new InspectorEvent("demo-001", mode, "book_flight", "ALLOW", "alice", agent, "travel", "travel-mcp", scope, spiffe, "Booking created", null));
        hub.publish(new InspectorEvent("demo-001", mode, "create_expense", "ALLOW", "alice", agent, "travel", "expense-mcp", scope, spiffe, "Expense created", null));
        var allowed = mode.equals("insecure");
        hub.publish(new InspectorEvent("demo-001", mode, "approve_expense", allowed ? "ALLOW" : "DENY", "alice", agent, "travel", "expense-mcp", scope, spiffe, allowed ? "Overprivileged service account" : "Missing expenses.approve scope", null));
    }
}
