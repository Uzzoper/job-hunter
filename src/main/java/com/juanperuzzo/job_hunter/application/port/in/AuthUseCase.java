package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.User;

public interface AuthUseCase {
    User register(String name, String email, String password);

    String login(String email, String password);
}
