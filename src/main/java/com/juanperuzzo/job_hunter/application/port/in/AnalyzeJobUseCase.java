package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;

public interface AnalyzeJobUseCase {
    JobAnalysis analyze(Job job);
}
