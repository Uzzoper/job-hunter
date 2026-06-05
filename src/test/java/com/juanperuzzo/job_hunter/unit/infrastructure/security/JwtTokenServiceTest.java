package com.juanperuzzo.job_hunter.unit.infrastructure.security;

import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("JwtTokenService tests")
class JwtTokenServiceTest {

    @Test
    @DisplayName("issue should create token containing user details")
    void issue_whenUserIsValid_shouldCreateTokenContainingUserDetails() {
        var jwtTokenService = new JwtTokenService("01234567890123456789012345678901", 24);
        var user = new User(1L, "juan@example.com", "Juan", "$2a$hash");

        var token = jwtTokenService.issue(user);
        var authenticatedUser = jwtTokenService.validate(token);

        assertEquals(1L, authenticatedUser.id());
        assertEquals("juan@example.com", authenticatedUser.email());
        assertEquals("Juan", authenticatedUser.name());
    }
}
