package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.AuthUseCase;
import com.juanperuzzo.job_hunter.web.dto.AuthRequest;
import com.juanperuzzo.job_hunter.web.dto.AuthResponse;
import com.juanperuzzo.job_hunter.web.dto.LoginRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUseCase authUseCase;

    public AuthController(AuthUseCase authUseCase) {
        this.authUseCase = authUseCase;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest request) {
        var result = authUseCase.register(request.name(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(result.token(), result.userId(), result.name(), result.email()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        var result = authUseCase.login(request.email(), request.password());
        return ResponseEntity.ok(new AuthResponse(result.token(), result.userId(), result.name(), result.email()));
    }
}
