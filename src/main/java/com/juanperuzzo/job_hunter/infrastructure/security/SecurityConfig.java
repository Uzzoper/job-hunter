package com.juanperuzzo.job_hunter.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security configuration for the self-hosted bot-only mode.
 * <p>
 * Authentication is handled by {@link BotTokenFilter} (X-Bot-Token). Spring Security
 * itself permits all requests (the token check lives in the filter). CSRF is disabled
 * because the API is consumed by the bot, not a browser.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}