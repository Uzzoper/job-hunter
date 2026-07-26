package com.juanperuzzo.job_hunter.infrastructure.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "user_projects")
public class UserProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "tech_stack", nullable = false, length = 500)
    private String techStack;

    public UserProjectEntity() {}

    public UserProjectEntity(Long id, Long userId, String name, String description, String techStack) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.techStack = techStack;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTechStack() { return techStack; }
    public void setTechStack(String techStack) { this.techStack = techStack; }
}
