package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

/**
 * Outbound port for normalizing raw job data into domain Jobs.
 * <p>
 * Implementations handle keyword matching, exclusion patterns, location filtering,
 * date parsing, and HTML entity decoding.
 */
public interface NormalizerPort {

    /**
     * Normalize a single raw job into a domain Job.
     *
     * @param raw the raw job data from a provider
     * @return the normalized Job, or null if the job should be skipped
     */
    Job normalize(RawJob raw);

    /**
     * Normalize a list of raw jobs, filtering out nulls.
     *
     * @param rawJobs list of raw jobs
     * @return list of normalized Jobs (skipped jobs omitted)
     */
    List<Job> normalizeAll(List<RawJob> rawJobs);
}