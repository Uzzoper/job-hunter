package com.juanperuzzo.job_hunter.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_profiles", uniqueConstraints = @UniqueConstraint(name = "uq_user_profiles_user_id", columnNames = "user_id"))
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "resume_text", nullable = false, columnDefinition = "TEXT")
    private String resumeText;

    @Column(name = "skills", nullable = false)
    @Convert(converter = StringListConverter.class)
    private String[] skills;

    @Column(name = "tone", length = 50)
    private String tone;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "portfolio_url", length = 500)
    private String portfolioUrl;

    @Column(name = "github_url", length = 500)
    private String githubUrl;

    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    // --- UserPreferences (nullable) ---

    @Column(name = "work_model", length = 20)
    private String workModel;

    @Column(name = "salary_floor")
    private Integer salaryFloor;

    @Column(name = "locations")
    @Convert(converter = StringListConverter.class)
    private String[] locations;

    @Column(name = "excluded_companies")
    @Convert(converter = StringListConverter.class)
    private String[] excludedCompanies;

    @Column(name = "updated_at", insertable = false, columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime updatedAt;

    public UserProfileEntity() {
    }

    public UserProfileEntity(Long id, Long userId, String resumeText, String[] skills, String tone,
                             String phone, String contactEmail, String portfolioUrl,
                             String githubUrl, String linkedinUrl,
                             String workModel, Integer salaryFloor,
                             String[] locations, String[] excludedCompanies) {
        this.id = id;
        this.userId = userId;
        this.resumeText = resumeText;
        this.skills = skills;
        this.tone = tone;
        this.phone = phone;
        this.contactEmail = contactEmail;
        this.portfolioUrl = portfolioUrl;
        this.githubUrl = githubUrl;
        this.linkedinUrl = linkedinUrl;
        this.workModel = workModel;
        this.salaryFloor = salaryFloor;
        this.locations = locations;
        this.excludedCompanies = excludedCompanies;
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

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getPortfolioUrl() { return portfolioUrl; }
    public void setPortfolioUrl(String portfolioUrl) { this.portfolioUrl = portfolioUrl; }

    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String githubUrl) { this.githubUrl = githubUrl; }

    public String getLinkedinUrl() { return linkedinUrl; }
    public void setLinkedinUrl(String linkedinUrl) { this.linkedinUrl = linkedinUrl; }

    public String getWorkModel() { return workModel; }
    public void setWorkModel(String workModel) { this.workModel = workModel; }

    public Integer getSalaryFloor() { return salaryFloor; }
    public void setSalaryFloor(Integer salaryFloor) { this.salaryFloor = salaryFloor; }

    public String[] getLocations() { return locations; }
    public void setLocations(String[] locations) { this.locations = locations; }

    public String[] getExcludedCompanies() { return excludedCompanies; }
    public void setExcludedCompanies(String[] excludedCompanies) { this.excludedCompanies = excludedCompanies; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
