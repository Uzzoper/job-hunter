package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class UserProfilePersistenceAdapter implements UserProfileRepository {

    private final UserProfileJpaRepository jpaRepository;

    public UserProfilePersistenceAdapter(UserProfileJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public UserProfile save(UserProfile profile) {
        var entity = toEntity(profile);
        var saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<UserProfile> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId).map(this::toDomain);
    }

    private UserProfileEntity toEntity(UserProfile profile) {
        return new UserProfileEntity(
                profile.id(),
                profile.userId(),
                profile.resumeText(),
                profile.skills().toArray(new String[0]),
                profile.tone().name()
        );
    }

    private UserProfile toDomain(UserProfileEntity entity) {
        List<String> skills = entity.getSkills() != null
                ? Arrays.asList(entity.getSkills())
                : List.of();
        CompanyTone tone = CompanyTone.valueOf(entity.getTone());
        return new UserProfile(
                entity.getId(),
                entity.getUserId(),
                entity.getResumeText(),
                skills,
                tone
        );
    }
}
