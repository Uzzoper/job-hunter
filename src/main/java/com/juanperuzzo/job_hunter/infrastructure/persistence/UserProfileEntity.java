package com.juanperuzzo.job_hunter.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_text", nullable = false, columnDefinition = "TEXT")
    private String resumeText;

    @Column(name = "skills", nullable = false, columnDefinition = "TEXT[]")
    private String[] skills;

    @Column(name = "tone", length = 50)
    private String tone;

    @Column(name = "updated_at", insertable = false, columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime updatedAt;

    public UserProfileEntity() {
    }

    public UserProfileEntity(Long id, Long userId, String resumeText, String[] skills, String tone) {
        this.id = id;
        this.userId = userId;
        this.resumeText = resumeText;
        this.skills = skills;
        this.tone = tone;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getResumeText() { return resumeText; }
    public void setResumeText(String resumeText) { this.resumeText = resumeText; }

    public String[] getSkills() { return skills; }
    public void setSkills(String[] skills) { this.skills = skills; }

    public String getTone() { return tone; }
    public void setTone(String tone) { this.tone = tone; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
