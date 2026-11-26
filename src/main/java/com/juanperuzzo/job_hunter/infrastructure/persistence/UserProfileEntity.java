package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_profiles")
public class UserProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "email", length = 255)
    private String email;

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
    // Notes: V7 added work_preference as the single sum-type column for the
    // Remote/Hybrid/Onsite variant. The legacy V6 columns work_model and locations
    // remain in the schema but are deliberately NOT mapped here — they are dead
    // columns kept only because the V6 migration must stay valid.

    @Column(name = "work_preference")
    @Convert(converter = WorkPreferenceConverter.class)
    private WorkPreference workPreference;

    @Column(name = "salary_floor")
    private Integer salaryFloor;

    @Column(name = "excluded_companies")
    @Convert(converter = StringListConverter.class)
    private String[] excludedCompanies;

    @Column(name = "keywords")
    @Convert(converter = StringListConverter.class)
    private String[] keywords;

    @Column(name = "updated_at", insertable = false, columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime updatedAt;

    public UserProfileEntity() {
    }

    public UserProfileEntity(Long id, String name, String email, String resumeText, String[] skills, String tone,
                             String phone, String contactEmail, String portfolioUrl,
                             String githubUrl, String linkedinUrl,
                             WorkPreference workPreference, Integer salaryFloor,
                             String[] excludedCompanies, String[] keywords) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.resumeText = resumeText;
        this.skills = skills;
        this.tone = tone;
        this.phone = phone;
        this.contactEmail = contactEmail;
        this.portfolioUrl = portfolioUrl;
        this.githubUrl = githubUrl;
        this.linkedinUrl = linkedinUrl;
        this.workPreference = workPreference;
        this.salaryFloor = salaryFloor;
        this.excludedCompanies = excludedCompanies;
        this.keywords = keywords;
    }

    public UserProfileEntity(Long id, String name, String email, String resumeText, String[] skills, String tone,
                             String phone, String contactEmail, String portfolioUrl,
                             String githubUrl, String linkedinUrl,
                             WorkPreference workPreference, Integer salaryFloor,
                             String[] excludedCompanies) {
        this(id, name, email, resumeText, skills, tone, phone, contactEmail, portfolioUrl,
                githubUrl, linkedinUrl, workPreference, salaryFloor, excludedCompanies, null);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

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

    public WorkPreference getWorkPreference() { return workPreference; }
    public void setWorkPreference(WorkPreference workPreference) { this.workPreference = workPreference; }

    public Integer getSalaryFloor() { return salaryFloor; }
    public void setSalaryFloor(Integer salaryFloor) { this.salaryFloor = salaryFloor; }

    public String[] getExcludedCompanies() { return excludedCompanies; }
    public void setExcludedCompanies(String[] excludedCompanies) { this.excludedCompanies = excludedCompanies; }

    public String[] getKeywords() { return keywords; }
    public void setKeywords(String[] keywords) { this.keywords = keywords; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}