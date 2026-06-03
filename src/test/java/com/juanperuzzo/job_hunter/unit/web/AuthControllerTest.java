package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.AuthResult;
import com.juanperuzzo.job_hunter.application.port.in.AuthUseCase;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadyExistsException;
import com.juanperuzzo.job_hunter.domain.exception.InvalidCredentialsException;
import com.juanperuzzo.job_hunter.web.controller.AuthController;
import com.juanperuzzo.job_hunter.web.dto.AuthRequest;
import com.juanperuzzo.job_hunter.web.dto.LoginRequest;
import com.juanperuzzo.job_hunter.web.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, excludeAutoConfiguration = SecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("AuthController tests")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthUseCase authUseCase;

    @MockitoBean
    private TokenProvider tokenProvider;

    @Test
    @DisplayName("register should return 201 and auth response when request is valid")
    void register_whenValidRequest_shouldReturn201() throws Exception {
        var request = new AuthRequest("Juan", "juan@test.com", "secret123");
        var authResult = new AuthResult("jwt-token-123", 1L, "Juan", "juan@test.com");

        when(authUseCase.register("Juan", "juan@test.com", "secret123")).thenReturn(authResult);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-token-123"))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.name").value("Juan"))
                .andExpect(jsonPath("$.email").value("juan@test.com"));
    }

    @Test
    @DisplayName("register should return 409 when email already exists")
    void register_whenEmailAlreadyExists_shouldReturn409() throws Exception {
        var request = new AuthRequest("Juan", "juan@test.com", "secret123");

        when(authUseCase.register("Juan", "juan@test.com", "secret123"))
                .thenThrow(new EmailAlreadyExistsException("Email already registered: juan@test.com"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("login should return 200 and auth response when credentials are valid")
    void login_whenValidCredentials_shouldReturn200() throws Exception {
        var request = new LoginRequest("juan@test.com", "secret123");
        var authResult = new AuthResult("jwt-token-123", 1L, "Juan", "juan@test.com");

        when(authUseCase.login("juan@test.com", "secret123")).thenReturn(authResult);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token-123"))
                .andExpect(jsonPath("$.userId").value(1));
    }

    @Test
    @DisplayName("login should return 401 when password is wrong")
    void login_whenWrongPassword_shouldReturn401() throws Exception {
        var request = new LoginRequest("juan@test.com", "wrongpass");

        when(authUseCase.login("juan@test.com", "wrongpass"))
                .thenThrow(new InvalidCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("login should return 401 when email is unknown")
    void login_whenUnknownEmail_shouldReturn401() throws Exception {
        var request = new LoginRequest("unknown@test.com", "secret123");

        when(authUseCase.login("unknown@test.com", "secret123"))
                .thenThrow(new InvalidCredentialsException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("register should return 400 when JSON is malformed")
    void register_whenInvalidJson_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid json}"))
                .andExpect(status().isBadRequest());
    }
}
