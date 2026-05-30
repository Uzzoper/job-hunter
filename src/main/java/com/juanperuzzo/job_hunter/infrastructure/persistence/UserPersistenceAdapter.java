package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.domain.model.User;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserPersistenceAdapter implements UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserPersistenceAdapter(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public User save(User user) {
        var entity = toEntity(user);
        var saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<User> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmail(email).map(this::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmail(email);
    }

    private UserEntity toEntity(User user) {
        return new UserEntity(user.id(), user.email(), user.name(), user.passwordHash());
    }

    private User toDomain(UserEntity entity) {
        return new User(entity.getId(), entity.getEmail(), entity.getName(), entity.getPasswordHash());
    }
}
