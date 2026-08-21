package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class UserProfilePersistenceAdapter implements UserProfileRepository {

    private final UserProfileJpaRepository jpaRepository;
    private final UserProjectJpaRepository projectJpaRepository;

    public UserProfilePersistenceAdapter(UserProfileJpaRepository jpaRepository,
                                          UserProjectJpaRepository projectJpaRepository) {
        this.jpaRepository = jpaRepository;
        this.projectJpaRepository = projectJpaRepository;
    }

    @Override
    @Transactional
    public UserProfile save(UserProfile profile) {
        var entity = toEntity(profile);
        var saved = jpaRepository.save(entity);

        if (profile.id() != null) {
            projectJpaRepository.deleteByUserId(profile.userId());
        }
        projectJpaRepository.flush();

        var projectEntities = profile.projects().stream()
                .map(p -> new UserProjectEntity(null, profile.userId(), p.name(), p.description(), p.techStack()))
                .toList();
        projectJpaRepository.saveAll(projectEntities);

        var savedProjects = projectJpaRepository.findByUserId(profile.userId()).stream()
                .map(pe -> new Project(pe.getName(), pe.getDescription(), pe.getTechStack()))
                .toList();
        return toDomain(saved, savedProjects);
    }

    @Override
    public Optional<UserProfile> findByUserId(Long userId) {
        return jpaRepository.findByUserId(userId).map(entity -> {
            var projects = projectJpaRepository.findByUserId(userId).stream()
                    .map(pe -> new Project(pe.getName(), pe.getDescription(), pe.getTechStack()))
                    .toList();
            return toDomain(entity, projects);
        });
    }

    private UserProfileEntity toEntity(UserProfile profile) {
        return new UserProfileEntity(
                profile.id(),
                profile.userId(),
                profile.resumeText(),
                profile.skills().toArray(new String[0]),
                profile.tone().name(),
                profile.phone(),
                profile.contactEmail(),
                profile.portfolioUrl(),
                profile.githubUrl(),
                profile.linkedinUrl()
        );
    }

    private UserProfile toDomain(UserProfileEntity entity, List<Project> projects) {
        List<String> skills = entity.getSkills() != null
                ? Arrays.asList(entity.getSkills())
                : List.of();
        CompanyTone tone = CompanyTone.valueOf(entity.getTone());
        return new UserProfile(
                entity.getId(),
                entity.getUserId(),
                entity.getResumeText(),
                skills,
                tone,
                projects,
                entity.getPhone(),
                entity.getContactEmail(),
                entity.getPortfolioUrl(),
                entity.getGithubUrl(),
                entity.getLinkedinUrl()
        );
    }
}
