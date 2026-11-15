package com.example.travel;

import com.example.travel.config.TravelAuthorization;
import com.example.travel.inspector.ToolDecisionPublisher;
import com.example.travel.mcp.TravelTools;
import com.example.travel.service.TravelService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Security { }

    @Test
    void methodSecurityReportsDeniedAndAllowedCallsBeforeBusinessExecution() {
        var publisher = mock(ToolDecisionPublisher.class);
        var service = mock(TravelService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Security.class);
            context.registerBean(ToolDecisionPublisher.class, () -> publisher);
            context.registerBean(TravelService.class, () -> service);
            context.registerBean("travelAuthorization", TravelAuthorization.class);
            context.registerBean(TravelTools.class);
            context.refresh();
            var tools = context.getBean(TravelTools.class);
            var denied = UsernamePasswordAuthenticationToken.authenticated("alice", "", List.of());
            SecurityContextHolder.getContext().setAuthentication(denied);
            assertThrows(AccessDeniedException.class, () -> tools.bookFlight("FL-100", "alice", "2026-11-15"));
            verify(publisher).publish(eq(denied), eq("book_flight"), eq(false), anyString());
            verifyNoInteractions(service);
            var allowed = UsernamePasswordAuthenticationToken.authenticated("alice", "",
                    List.of(new SimpleGrantedAuthority("SCOPE_travel.read")));
            SecurityContextHolder.getContext().setAuthentication(allowed);
            tools.getBooking("BOOK-100");
            verify(publisher).publish(eq(allowed), eq("get_booking"), eq(true), anyString());
            verify(service).getBooking("BOOK-100");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
