package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.AuthUseCase;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.web.dto.AuthRequest;
import com.juanperuzzo.job_hunter.web.dto.AuthResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUseCase authUseCase;
    private final TokenProvider tokenProvider;

    public AuthController(AuthUseCase authUseCase, TokenProvider tokenProvider) {
        this.authUseCase = authUseCase;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        var user = authUseCase.register(request.name(), request.email(), request.password());
        var token = tokenProvider.issue(user);
        var response = new AuthResponse(token, user.id(), user.name(), user.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        var token = authUseCase.login(request.email(), request.password());
        var user = tokenProvider.validate(token);
        var response = new AuthResponse(token, user.id(), user.name(), user.email());
        return ResponseEntity.ok(response);
    }
}
