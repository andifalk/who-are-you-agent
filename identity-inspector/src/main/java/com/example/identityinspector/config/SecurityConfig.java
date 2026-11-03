package com.example.identityinspector.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, @Value("${inspector.api-key:}") String key) throws Exception {
        if (key.isBlank()) throw new IllegalStateException("Set INSPECTOR_API_KEY before starting Identity Inspector");
        var apiKeyFilter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
                if (req.getRequestURI().startsWith("/api/") && req.getMethod().equals("POST")) {
                    var supplied = req.getHeader("X-Inspector-Key");
                    if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
                        res.sendError(403);
                        return;
                    }
                }
                chain.doFilter(req, res);
            }
        };
        http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(a -> a.anyRequest().permitAll()).addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
