package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class UserProfilePersistenceAdapter implements UserProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(UserProfilePersistenceAdapter.class);

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
        var prefs = profile.preferences();
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
                profile.linkedinUrl(),
                prefs != null && prefs.workModel() != null ? prefs.workModel().name() : null,
                prefs != null ? prefs.salaryFloor() : null,
                prefs != null && !prefs.locations().isEmpty()
                        ? prefs.locations().toArray(new String[0]) : null,
                prefs != null && !prefs.excludedCompanies().isEmpty()
                        ? prefs.excludedCompanies().toArray(new String[0]) : null
        );
    }

    private UserProfile toDomain(UserProfileEntity entity, List<Project> projects) {
        List<String> skills = entity.getSkills() != null
                ? Arrays.asList(entity.getSkills())
                : List.of();
        CompanyTone tone = CompanyTone.valueOf(entity.getTone());

        UserPreferences preferences = buildPreferences(entity);

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
                entity.getLinkedinUrl(),
                preferences
        );
    }

    private UserPreferences buildPreferences(UserProfileEntity entity) {
        WorkModel workModel = parseWorkModelSafely(entity.getWorkModel());
        Integer salaryFloor = entity.getSalaryFloor();
        List<String> locations = entity.getLocations() != null
                ? Arrays.asList(entity.getLocations()) : null;
        List<String> excludedCompanies = entity.getExcludedCompanies() != null
                ? Arrays.asList(entity.getExcludedCompanies()) : null;

        // Only build if at least one field is set
        if (workModel == null && salaryFloor == null
                && (locations == null || locations.isEmpty())
                && (excludedCompanies == null || excludedCompanies.isEmpty())) {
            return null;
        }
        return new UserPreferences(workModel, salaryFloor, locations, excludedCompanies);
    }

    /**
     * Tolerant read of the {@code work_model} column: raw DB text is converted
     * to the enum only when it matches a known value. Garbage values (e.g. from
     * a hand-edited DB or a future migration) are dropped with a WARN log and
     * yield {@code null} instead of failing the entire profile read.
     */
    private WorkModel parseWorkModelSafely(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WorkModel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Dropping invalid work_model value '{}' stored in database — treating as unset", raw);
            return null;
        }
    }
}
