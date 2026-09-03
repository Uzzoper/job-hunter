package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.domain.model.Job;

import java.util.List;

/**
 * Result of a full provider scrape: the normalized, de-duplicated jobs plus the
 * per-provider raw fetch statistics. Persistence (and therefore {@code saved} /
 * {@code withEmail}) is resolved later by the application service.
 *
 * @param jobs       normalized jobs (after enrichment, before persistence dedup)
 * @param perProvider raw per-provider statistics (fetched/detailFailedCount/error populated)
 */
public record ScraperResult(
        List<Job> jobs,
        List<ProviderFetchStats> perProvider
) {}
