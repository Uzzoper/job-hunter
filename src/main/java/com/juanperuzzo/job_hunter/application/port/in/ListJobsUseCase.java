package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

public interface ListJobsUseCase {
    List<Job> findAll();
}
