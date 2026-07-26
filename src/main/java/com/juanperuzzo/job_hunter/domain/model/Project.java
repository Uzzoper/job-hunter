package com.juanperuzzo.job_hunter.domain.model;

import static java.util.Objects.requireNonNull;

public record Project(
        String name,
        String description,
        String techStack
) {
    public Project {
        requireNonNull(name, "name must not be null");
        requireNonNull(description, "description must not be null");
        requireNonNull(techStack, "techStack must not be null");
    }
}
