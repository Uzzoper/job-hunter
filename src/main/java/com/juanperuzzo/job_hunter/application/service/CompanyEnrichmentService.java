package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.CompanyEnrichmentUseCase;
import com.juanperuzzo.job_hunter.application.port.in.EnrichmentResult;
import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.PortalDomains;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enriches jobs with company-site contact emails as an explicit, out-of-band batch
 * (async-company-enrichment spec). Runs one crawl per distinct company domain and
 * applies the discovered email to every job of that domain in the batch, persisting
 * each update in place (JPA merge, no duplicate rows).
 * <p>
 * Runs outside the synchronous {@code POST /api/jobs/fetch} hot path and never throws:
 * per-domain failures are counted and the batch continues.
 * <p>
 * The {@code failed} counter tracks only genuine failures — exceptions during crawl,
 * persistence save failures, and robots-denials. A domain that was crawled successfully
 * but yielded no email is {@code not} a failure (it is simply unenriched).
 */
public class CompanyEnrichmentService implements CompanyEnrichmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompanyEnrichmentService.class);

    private static final int DEFAULT_MAX_LIMIT = 200;

    private final JobRepository jobRepository;
    private final CompanySiteEnrichmentPort enricher;
    private final int maxLimit;

    public CompanyEnrichmentService(JobRepository jobRepository, CompanySiteEnrichmentPort enricher) {
        this(jobRepository, enricher, DEFAULT_MAX_LIMIT);
    }

    public CompanyEnrichmentService(JobRepository jobRepository, CompanySiteEnrichmentPort enricher, int maxLimit) {
        this.jobRepository = jobRepository;
        this.enricher = enricher;
        this.maxLimit = maxLimit > 0 ? maxLimit : DEFAULT_MAX_LIMIT;
    }

    @Override
    public EnrichmentResult enrichMissingEmails(int limit) {
        int clamped = Math.max(1, Math.min(limit, maxLimit));
        List<Job> candidates;
        try {
            candidates = jobRepository.findJobsNeedingEnrichment(clamped);
        } catch (Exception e) {
            log.error("failed to load jobs needing enrichment: {}", e.getMessage());
            return new EnrichmentResult(0, 0, 0, 0);
        }

        int checked = candidates.size();
        int enriched = 0;
        int skippedPortal = 0;
        int failed = 0;

        // Group jobs by lowercase host of companyWebsite, preserving first-seen order.
        Map<String, List<Job>> byDomain = new LinkedHashMap<>();
        for (var job : candidates) {
            var host = host(job.companyWebsite());
            if (host == null) {
                // No resolvable domain — cannot crawl; not a "failure" in the strict sense.
                continue;
            }
            byDomain.computeIfAbsent(host, k -> new ArrayList<>()).add(job);
        }

        for (var entry : byDomain.entrySet()) {
            var domain = entry.getKey();
            var domainJobs = entry.getValue();

            if (PortalDomains.isPortal(domain)) {
                skippedPortal += domainJobs.size();
                continue;
            }

            try {
                String email = null;
                // One crawl: enrich a single representative job, then apply email to all.
                var representative = domainJobs.get(0);
                var enrichedJob = enricher.enrich(representative);
                if (enrichedJob != null && enrichedJob.contactEmail() != null) {
                    email = enrichedJob.contactEmail();
                }

                if (email == null) {
                    // Crawled successfully but no email found — Unenriched, not failed.
                    continue;
                }

                for (var job : domainJobs) {
                    try {
                        jobRepository.save(job.withContactEmail(email));
                        enriched++;
                    } catch (Exception e) {
                        log.warn("failed to save enrichment for job {}: {}", job.url(), e.getMessage());
                        failed++;
                    }
                }
            } catch (Exception e) {
                log.warn("company-site enrichment failed for domain {}: {}", domain, e.getMessage());
                failed += domainJobs.size();
            }
        }

        return new EnrichmentResult(checked, enriched, skippedPortal, failed);
    }

    private static String host(String url) {
        try {
            var host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
