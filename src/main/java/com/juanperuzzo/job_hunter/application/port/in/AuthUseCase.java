package com.juanperuzzo.job_hunter.application.port.in;

public interface AuthUseCase {
    AuthResult register(String name, String email, String password);

    AuthResult login(String email, String password);
}
