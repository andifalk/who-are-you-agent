package de.agiledev.agent.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

@Component
public class SessionModeStore {
    private static final String KEY = "demo.mode";

    public DemoMode get(HttpSession session) {
        Object mode = session.getAttribute(KEY);
        return mode instanceof DemoMode d ? d : DemoMode.DELEGATED;
    }

    public void set(HttpSession session, DemoMode mode) {
        session.setAttribute(KEY, mode);
    }
}
