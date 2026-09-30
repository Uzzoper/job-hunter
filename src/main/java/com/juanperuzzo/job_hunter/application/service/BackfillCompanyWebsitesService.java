package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.BackfillCompanyWebsitesUseCase;
import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;

/**
 * Backfills missing {@code companyWebsite} values on stored Gupy rows
 * (gupy-detail-domains spec §7) — implementation in progress.
 */
public class BackfillCompanyWebsitesService implements BackfillCompanyWebsitesUseCase {

    private final JobRepository jobRepository;
    private final CompanyDomainResolverPort companyDomainResolver;

    public BackfillCompanyWebsitesService(
            JobRepository jobRepository,
            CompanyDomainResolverPort companyDomainResolver) {
        this.jobRepository = jobRepository;
        this.companyDomainResolver = companyDomainResolver;
    }

    @Override
    public Result run(boolean dryRun, int maxHosts) {
        return new Result(0, 0, 0);
    }
}