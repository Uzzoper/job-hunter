package com.juanperuzzo.job_hunter.application.port.out;

import com.juanperuzzo.job_hunter.domain.model.Job;

/**
 * Outbound port for enriching a Job by crawling its company website to discover a
 * contact email (email-enrichment spec, P2). Implementations are conservative
 * (rate-limited, robots.txt-aware, cached) and must never throw — returning the
 * job unchanged on any failure.
 */
public interface CompanySiteEnrichmentPort {

    /**
     * Enrich the given job with a contact email crawled from its company website.
     * Returns the same {@link Job} reference when no enrichment is possible/applicable.
     *
     * @param job the job to enrich (may be null)
     * @return the enriched job, or the original job when nothing to do
     */
    Job enrich(Job job);
}
