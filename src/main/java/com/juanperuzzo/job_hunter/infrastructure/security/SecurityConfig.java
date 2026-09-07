package com.juanperuzzo.job_hunter.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenFilter jwtTokenFilter;
    private final BotTokenFilter botTokenFilter;
    private final String allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;

    public SecurityConfig(
            JwtTokenFilter jwtTokenFilter,
            BotTokenFilter botTokenFilter,
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:4200}") String allowedOrigins,
            @Value("${app.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}") String allowedMethods,
            @Value("${app.cors.allowed-headers:*}") String allowedHeaders) {
        this.jwtTokenFilter = jwtTokenFilter;
        this.botTokenFilter = botTokenFilter;
        this.allowedOrigins = allowedOrigins;
        this.allowedMethods = allowedMethods;
        this.allowedHeaders = allowedHeaders;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json");
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    Map<String, Object> body = GlobalExceptionHandler.buildErrorBody(HttpStatus.UNAUTHORIZED, "Unauthorized");
                    response.getWriter().write(new ObjectMapper().writeValueAsString(body));
                })
            )
            // BotTokenFilter runs first; JwtTokenFilter anchors on the always-registered
            // UsernamePasswordAuthenticationFilter. Sequence: [BotTokenFilter, JwtTokenFilter, UPAF].
            .addFilterBefore(botTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<BotTokenFilter> botTokenFilterRegistration(BotTokenFilter botTokenFilter) {
        FilterRegistrationBean<BotTokenFilter> registration = new FilterRegistrationBean<>(botTokenFilter);
        // The filter must run ONLY inside the SecurityFilterChain (after SecurityContext
        // setup), so disable its servlet auto-registration. Otherwise it would run outside
        // the chain and its principal would be discarded before authorization.
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtTokenFilter> jwtTokenFilterRegistration(JwtTokenFilter jwtTokenFilter) {
        FilterRegistrationBean<JwtTokenFilter> registration = new FilterRegistrationBean<>(jwtTokenFilter);
        // Mirror BotTokenFilter: the JWT filter must run ONLY inside the SecurityFilterChain.
        // As a plain servlet filter it would run before the chain in sliced test contexts and
        // its OncePerRequestFilter marker would make the in-chain copy skip, masking the
        // [BotTokenFilter, JwtTokenFilter] precedence contract. Production is unaffected:
        // the FilterChainProxy (order -100) always ran first anyway.
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(Arrays.asList(allowedMethods.split(",")));
        configuration.setAllowedHeaders(List.of(allowedHeaders));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
