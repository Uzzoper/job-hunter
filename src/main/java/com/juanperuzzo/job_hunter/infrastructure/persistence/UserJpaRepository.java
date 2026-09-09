package com.juanperuzzo.job_hunter.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Project only {@code email} (no fetch of whole entities) for the self-match guard. */
    @Query("select u.email from UserEntity u")
    List<String> findAllEmails();
}
