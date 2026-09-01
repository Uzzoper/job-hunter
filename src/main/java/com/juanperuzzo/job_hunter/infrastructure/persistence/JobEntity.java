package com.juanperuzzo.job_hunter.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "company", nullable = false, length = 255)
    private String company;

    @Column(name = "url", nullable = false, unique = true, length = 500)
    private String url;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "posted_at", nullable = false)
    private LocalDate postedAt;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "company_website", length = 512)
    private String companyWebsite;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false, columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime createdAt;

    public JobEntity() {
    }

    public JobEntity(Long id, String title, String company, String url, String description, LocalDate postedAt, String source, String contactEmail, String companyWebsite) {
        this.id = id;
        this.title = title;
        this.company = company;
        this.url = url;
        this.description = description;
        this.postedAt = postedAt;
        this.source = source;
        this.contactEmail = contactEmail;
        this.companyWebsite = companyWebsite;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(LocalDate postedAt) {
        this.postedAt = postedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getCompanyWebsite() {
        return companyWebsite;
    }

    public void setCompanyWebsite(String companyWebsite) {
        this.companyWebsite = companyWebsite;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
