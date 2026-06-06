package com.juanperuzzo.job_hunter.application.port.in;

import com.juanperuzzo.job_hunter.domain.model.Job;

public interface GetJobUseCase {
    Job getById(Long id);
}
