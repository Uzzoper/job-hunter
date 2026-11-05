package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.exception.DuplicateUrlException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JobPersistenceAdapter implements JobRepository {

    private final JobJpaRepository jpaRepository;

    public JobPersistenceAdapter(JobJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public boolean existsByUrl(String url) {
        return jpaRepository.existsByUrl(url);
    }

    @Override
    public Job save(Job job) {
        try {
            JobEntity saved = jpaRepository.save(toEntity(job));
            return toDomain(saved);
        } catch (DataIntegrityViolationException e) {
            // SQLite wording: only the jobs.url UNIQUE violation is a duplicate
            // identity (Job identity is URL-based); anything else is a real
            // integrity error and must surface untouched.
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE constraint failed: jobs.url")) {
                throw new DuplicateUrlException("a job with this url already exists: " + job.url());
            }
            throw e;
        }
    }

    @Override
    public List<Job> findAll() {
        return jpaRepository.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Job> findAllByContactEmailIsNotNull() {
        return jpaRepository.findByContactEmailIsNotNull().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Job> findAllByContactEmailIsNull() {
        return jpaRepository.findByContactEmailIsNull().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Job> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Job> findJobsNeedingEnrichment(int limit) {
        int size = Math.max(limit, 1);
        return jpaRepository.findJobsNeedingEnrichment(PageRequest.of(0, size)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Job> findBySourceAndCompanyWebsiteIsNull(String source) {
        return jpaRepository.findBySourceAndCompanyWebsiteIsNull(source).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Job> findBySourceAndCompanyWebsiteIsNullAfterId(String source, long afterId) {
        return jpaRepository.findBySourceAndCompanyWebsiteIsNullAfterId(source, afterId).stream()
                .map(this::toDomain)
                .toList();
    }

    private JobEntity toEntity(Job job) {
        return new JobEntity(
                job.id(),
                job.title(),
                job.company(),
                job.url(),
                job.description(),
                job.postedAt(),
                job.source(),
                job.contactEmail(),
                job.companyWebsite(),
                job.emailSuspect()
        );
    }

    private Job toDomain(JobEntity entity) {
        return new Job(
                entity.getId(),
                entity.getTitle(),
                entity.getCompany(),
                entity.getUrl(),
                entity.getDescription(),
                entity.getPostedAt(),
                entity.getSource(),
                entity.getContactEmail(),
                entity.getCompanyWebsite(),
                entity.isEmailSuspect()
        );
    }
}
