package com.juanperuzzo.job_hunter.domain.model;

import static java.util.Objects.requireNonNull;

public record User(
        Long id,
        String email,
        String name,
        String passwordHash
) {
    public User {
        requireNonNull(email, "email must not be null");
        requireNonNull(name, "name must not be null");
        requireNonNull(passwordHash, "passwordHash must not be null");
    }
}
