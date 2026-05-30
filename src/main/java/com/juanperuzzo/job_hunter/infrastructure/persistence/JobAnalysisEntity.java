package com.juanperuzzo.job_hunter.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "job_analyses", uniqueConstraints = @UniqueConstraint(columnNames = {"job_id", "user_id"}))
public class JobAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "match_score", nullable = false)
    private Integer matchScore;

    @Column(name = "matched_skills", nullable = false, columnDefinition = "TEXT[]")
    private String[] matchedSkills;

    @Column(name = "missing_skills", nullable = false, columnDefinition = "TEXT[]")
    private String[] missingSkills;

    @Column(name = "company_tone", nullable = false, length = 50)
    private String companyTone;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "analyzed_at", insertable = false, columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime analyzedAt;

    public JobAnalysisEntity() {
    }

    public JobAnalysisEntity(Long id, Long jobId, Long userId, Integer matchScore,
                             String[] matchedSkills, String[] missingSkills,
                             String companyTone, String summary) {
        this.id = id;
        this.jobId = jobId;
        this.userId = userId;
        this.matchScore = matchScore;
        this.matchedSkills = matchedSkills;
        this.missingSkills = missingSkills;
        this.companyTone = companyTone;
        this.summary = summary;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Integer getMatchScore() { return matchScore; }
    public void setMatchScore(Integer matchScore) { this.matchScore = matchScore; }

    public String[] getMatchedSkills() { return matchedSkills; }
    public void setMatchedSkills(String[] matchedSkills) { this.matchedSkills = matchedSkills; }

    public String[] getMissingSkills() { return missingSkills; }
    public void setMissingSkills(String[] missingSkills) { this.missingSkills = missingSkills; }

    public String getCompanyTone() { return companyTone; }
    public void setCompanyTone(String companyTone) { this.companyTone = companyTone; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public LocalDateTime getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(LocalDateTime analyzedAt) { this.analyzedAt = analyzedAt; }
}
