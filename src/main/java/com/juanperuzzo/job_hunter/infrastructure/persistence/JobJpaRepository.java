package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobJpaRepository extends JpaRepository<JobEntity, Long> {

    boolean existsByUrl(String url);

    List<JobEntity> findByContactEmailIsNotNull();

    List<JobEntity> findByContactEmailIsNull();
}
