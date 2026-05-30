package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import java.util.Optional;

public interface JobAnalysisRepository {
    JobAnalysis save(JobAnalysis analysis);
    Optional<JobAnalysis> findByJobIdAndUserId(Long jobId, Long userId);
}
