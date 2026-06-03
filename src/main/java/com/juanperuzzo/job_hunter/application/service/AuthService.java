package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.AuthResult;
import com.juanperuzzo.job_hunter.application.port.in.AuthUseCase;
import com.juanperuzzo.job_hunter.application.port.out.PasswordHasher;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.domain.exception.EmailAlreadyExistsException;
import com.juanperuzzo.job_hunter.domain.exception.InvalidCredentialsException;
import com.juanperuzzo.job_hunter.domain.model.User;

import java.util.Locale;

public class AuthService implements AuthUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenProvider tokenProvider;

    public AuthService(UserRepository userRepository, PasswordHasher passwordHasher, TokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public AuthResult register(String name, String email, String password) {
        var normalized = normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new EmailAlreadyExistsException("Email already registered: " + normalized);
        }

        var hash = passwordHasher.hash(password);
        var user = userRepository.save(new User(null, normalized, name, hash));
        var token = tokenProvider.issue(user);
        return new AuthResult(token, user.id(), user.name(), user.email());
    }

    @Override
    public AuthResult login(String email, String password) {
        var user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordHasher.matches(password, user.passwordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        var token = tokenProvider.issue(user);
        return new AuthResult(token, user.id(), user.name(), user.email());
    }

    private String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT).trim();
    }
}
