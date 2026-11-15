package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.User;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
    User save(User user);
    Optional<User> findById(Long id);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<String> findAllEmails();
    List<User> findAll();

    /**
     * Updates the account identity (name + login email) of the given user
     * (issue #92 slice b). The users row remains the identity source of truth.
     */
    void updateIdentity(Long userId, String name, String email);
}
