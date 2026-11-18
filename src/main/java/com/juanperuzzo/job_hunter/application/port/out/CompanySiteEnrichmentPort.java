package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.Job;

/**
 * Outbound port for enriching a Job by crawling its company website to discover a
 * contact email (email-enrichment spec, P2). Implementations are conservative
 * (rate-limited, robots.txt-aware, cached). Crawl failures may throw so the batch
 * use case can count them; the batch remains non-fatal and continues with other domains.
 */
public interface CompanySiteEnrichmentPort {

    /**
     * Enrich the given job with a contact email crawled from its company website.
     * Returns the same {@link Job} reference when no enrichment is possible/applicable.
     * Implementations may throw a runtime exception when every eligible page fails to load.
     *
     * @param job the job to enrich (may be null)
     * @return the enriched job, or the original job when nothing to do
     */
    Job enrich(Job job);
}
