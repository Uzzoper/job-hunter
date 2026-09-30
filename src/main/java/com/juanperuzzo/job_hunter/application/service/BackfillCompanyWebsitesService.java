package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.BackfillCompanyWebsitesUseCase;
import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backfills missing {@code companyWebsite} values on stored Gupy rows
 * (gupy-detail-domains spec §7). One-time migration remedy for the pre-fetch
 * backlog: rows the fetch path deduplicated away never got a domain, so this
 * use case scans them and resolves via the exact same resolver the fetch path
 * uses — no duplicated host-grouping or link policy.
 *
 * <p>The scan set is exactly {@code findBySourceAndCompanyWebsiteIsNull}:
 * existing websites are never candidates, so nothing can ever overwrite them.
 * Dry-run returns the same counts as apply without persisting, giving an
 * idempotent, rerunnable preview; on apply only the null→found transitions are
 * written (URL-based {@link Job} identity is preserved by
 * {@code withCompanyWebsite}, which copies every other field). {@code maxHosts}
 * bounds the hosts resolved per call — repeat until {@code stillNull} stops
 * shrinking.
 */
public class BackfillCompanyWebsitesService implements BackfillCompanyWebsitesUseCase {

    private static final Logger log = LoggerFactory.getLogger(BackfillCompanyWebsitesService.class);
    private static final String SOURCE = "gupy";

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
        var candidates = jobRepository.findBySourceAndCompanyWebsiteIsNull(SOURCE);
        var scanned = candidates.size();
        var filled = 0;

        if (scanned > 0) {
            var resolved = companyDomainResolver.resolveCompanyWebsites(
                    candidates.stream().map(Job::url).toList(), maxHosts);
            for (var job : candidates) {
                var website = resolved.get(job.url());
                if (website == null) {
                    continue;
                }
                if (!dryRun) {
                    jobRepository.save(job.withCompanyWebsite(website));
                }
                filled++;
            }
        }

        var result = new Result(scanned, filled, scanned - filled);
        log.info("Company-website backfill (dryRun={}, maxHosts={}): scanned={}, filled={}, stillNull={}",
                dryRun, maxHosts, result.scanned(), result.filled(), result.stillNull());
        return result;
    }
}