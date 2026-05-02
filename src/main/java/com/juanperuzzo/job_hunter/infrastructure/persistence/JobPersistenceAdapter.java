package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.model.Job;
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
        JobEntity entity = toEntity(job);
        JobEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public List<Job> findAll() {
        return jpaRepository.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<Job> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    private JobEntity toEntity(Job job) {
        return new JobEntity(
                job.id(),
                job.title(),
                job.company(),
                job.url(),
                job.description(),
                job.postedAt(),
                job.matchScore().orElse(null)
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
                Optional.ofNullable(entity.getMatchScore())
        );
    }
}
