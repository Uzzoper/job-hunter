package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.PasswordHasher;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.service.AuthService;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadyExistsException;
import com.juanperuzzo.job_hunter.domain.exception.InvalidCredentialsException;
import com.juanperuzzo.job_hunter.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService tests")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TokenProvider tokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordHasher, tokenProvider);
    }

    @Test
    @DisplayName("register should hash password and save user when email is new")
    void register_whenEmailIsNew_shouldHashPasswordAndSaveUser() {
        var savedUser = new User(1L, "juan@example.com", "Juan", "$2a$hash");

        when(userRepository.existsByEmail("juan@example.com")).thenReturn(false);
        when(passwordHasher.hash("secret123")).thenReturn("$2a$hash");
        when(userRepository.save(new User(null, "juan@example.com", "Juan", "$2a$hash"))).thenReturn(savedUser);

        var registered = authService.register("Juan", "juan@example.com", "secret123");

        assertEquals(savedUser, registered);

        var userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals("$2a$hash", userCaptor.getValue().passwordHash());
    }

    @Test
    @DisplayName("register should throw EmailAlreadyExistsException when email is already registered")
    void register_whenEmailAlreadyExists_shouldThrowEmailAlreadyExistsException() {
        when(userRepository.existsByEmail("juan@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class,
                () -> authService.register("Juan", "juan@example.com", "secret123"));
    }

    @Test
    @DisplayName("login should return token when credentials are valid")
    void login_whenCredentialsAreValid_shouldReturnToken() {
        var user = new User(1L, "juan@example.com", "Juan", "$2a$hash");

        when(userRepository.findByEmail("juan@example.com")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("secret123", "$2a$hash")).thenReturn(true);
        when(tokenProvider.issue(user)).thenReturn("jwt-token");

        var token = authService.login("juan@example.com", "secret123");

        assertEquals("jwt-token", token);
    }

    @Test
    @DisplayName("login should throw InvalidCredentialsException when password does not match")
    void login_whenPasswordDoesNotMatch_shouldThrowInvalidCredentialsException() {
        var user = new User(1L, "juan@example.com", "Juan", "$2a$hash");

        when(userRepository.findByEmail("juan@example.com")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("wrong", "$2a$hash")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("juan@example.com", "wrong"));
    }

    @Test
    @DisplayName("login should throw InvalidCredentialsException when email is unknown")
    void login_whenEmailIsUnknown_shouldThrowInvalidCredentialsException() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login("missing@example.com", "secret123"));
    }
}
