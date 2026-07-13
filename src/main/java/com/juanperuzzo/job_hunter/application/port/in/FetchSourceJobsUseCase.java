package com.juanperuzzo.job_hunter.application.port.in;

public interface FetchSourceJobsUseCase {
    void fetchAndSave(String sourceId);
}
