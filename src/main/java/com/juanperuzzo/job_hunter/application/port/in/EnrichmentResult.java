package com.juanperuzzo.job_hunter.application.port.in;

/**
 * Result of a company-site enrichment batch run (async-company-enrichment spec).
 *
 * @param checked      number of candidate jobs examined
 * @param enriched     number of jobs whose contactEmail was set from a company-site crawl
 * @param skippedPortal number of jobs skipped because they belong to known portal domains
 * @param failed       number of jobs whose domain crawl failed (unchanged, non-fatal)
 */
public record EnrichmentResult(int checked, int enriched, int skippedPortal, int failed) {}
