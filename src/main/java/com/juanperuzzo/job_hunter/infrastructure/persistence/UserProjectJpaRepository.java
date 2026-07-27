package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserProjectJpaRepository extends JpaRepository<UserProjectEntity, Long> {
    List<UserProjectEntity> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
