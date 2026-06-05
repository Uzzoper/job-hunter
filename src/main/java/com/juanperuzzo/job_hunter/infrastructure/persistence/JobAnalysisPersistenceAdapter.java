package com.juanperuzzo.job_hunter.infrastructure.persistence;

import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class JobAnalysisPersistenceAdapter implements JobAnalysisRepository {

    private final JobAnalysisJpaRepository jpaRepository;

    public JobAnalysisPersistenceAdapter(JobAnalysisJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public JobAnalysis save(JobAnalysis analysis) {
        var entity = toEntity(analysis);
        var saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<JobAnalysis> findByJobIdAndUserId(Long jobId, Long userId) {
        return jpaRepository.findByJobIdAndUserId(jobId, userId).map(this::toDomain);
    }

    private JobAnalysisEntity toEntity(JobAnalysis analysis) {
        return new JobAnalysisEntity(
                analysis.id(),
                analysis.jobId(),
                analysis.userId(),
                analysis.matchScore(),
                analysis.matchedSkills().toArray(new String[0]),
                analysis.missingSkills().toArray(new String[0]),
                analysis.companyTone().name(),
                analysis.summary()
        );
    }

    private JobAnalysis toDomain(JobAnalysisEntity entity) {
        List<String> matchedSkills = entity.getMatchedSkills() != null
                ? Arrays.asList(entity.getMatchedSkills())
                : List.of();
        List<String> missingSkills = entity.getMissingSkills() != null
                ? Arrays.asList(entity.getMissingSkills())
                : List.of();
        CompanyTone tone = CompanyTone.valueOf(entity.getCompanyTone());
        return new JobAnalysis(
                entity.getId(),
                entity.getJobId(),
                entity.getUserId(),
                entity.getMatchScore(),
                matchedSkills,
                missingSkills,
                tone,
                entity.getSummary()
        );
    }
}
