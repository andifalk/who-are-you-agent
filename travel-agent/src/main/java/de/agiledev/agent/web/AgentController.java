package de.agiledev.agent.web;

import de.agiledev.agent.TravelBookingAgent;
import de.agiledev.agent.auth.DelegatedTokenProvider;
import de.agiledev.agent.auth.DemoMode;
import de.agiledev.agent.auth.SessionModeStore;
import de.agiledev.agent.inspector.InspectorPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class AgentController {
    private final TravelBookingAgent agent;
    private final DelegatedTokenProvider tokens;
    private final SessionModeStore modes;
    private final InspectorPublisher inspector;

    public AgentController(TravelBookingAgent agent, DelegatedTokenProvider tokens, SessionModeStore modes, InspectorPublisher inspector) {
        this.agent = agent;
        this.tokens = tokens;
        this.modes = modes;
        this.inspector = inspector;
    }

    @GetMapping("/")
    public String home(CsrfToken csrf) {
        csrf.getToken();
        return "forward:/index.html";
    }

    @GetMapping("/api/session")
    @ResponseBody
    public Map<String, String> session(HttpSession session, Authentication user) {
        return Map.of("user", user.getName(), "mode", modes.get(session).name());
    }

    @PostMapping("/api/mode")
    @ResponseBody
    public Map<String, String> mode(@RequestBody ModeRequest payload,
                                    HttpSession session, Authentication user) {
        if (payload.mode() == null) throw new IllegalArgumentException("mode required");
        modes.set(session, payload.mode());
        // Session-wide mode selection; token caches keyed by target and mode are cleared.
        for (var target : List.of("TRAVEL", "EXPENSE")) {
            session.removeAttribute("token3." + target);
            session.removeAttribute("service.token." + target);
        }
        return Map.of("mode", payload.mode().name());
    }

    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<?> chat(
            @RequestBody ChatRequest input, HttpServletRequest request, Authentication user) {
        if (input.message() == null || input.message().isBlank() || input.message().length() > 3000)
            return ResponseEntity.badRequest().body(Map.of("error", "Message must be 1..3000 chars"));
        String id = UUID.randomUUID().toString();
        DemoMode mode = modes.get(request.getSession());
        try {
            String travel = tokens.tokenFor(DelegatedTokenProvider.Resource.TRAVEL, mode, user, request, id);
            String expense = tokens.tokenFor(DelegatedTokenProvider.Resource.EXPENSE, mode, user, request, id);
            return ResponseEntity.ok(agent.run(input.message(), travel, expense, id));
        } catch (Exception e) {
            inspector.publish("AGENT_ERROR", "", "ERROR", user.getName(), "travel-agent",
                    "Agent call failed; inspect server logs without token material", id);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error",
                    "Agent/Keycloak/MCP error. Check server logs and Inspector.", "correlationId", id));
        }
    }

    public record ModeRequest(DemoMode mode) {
    }

    public record ChatRequest(String message) {
    }
}
