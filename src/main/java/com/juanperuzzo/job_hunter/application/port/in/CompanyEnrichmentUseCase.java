package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Use case for enriching jobs with company-site contact emails out-of-band
 * (async-company-enrichment spec). The enrichment runs as an explicit batch,
 * never inside the synchronous fetch hot path.
 */
public interface CompanyEnrichmentUseCase {

    /**
     * Crawl company websites for jobs missing a contact email, one crawl per
     * distinct domain, and persist any found emails back to the existing rows.
     *
     * @param limit maximum number of candidate jobs to examine (clamped to [1, max])
     * @return aggregated result of the batch run; never throws
     */
    EnrichmentResult enrichMissingEmails(int limit);
}
