package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * Resolves a company website URL per career-page host from job detail pages.
 *
 * <p>Shared by the Gupy fetch path, the Greenhouse fetch path and the
 * company-website backfill (gupy-detail-domains spec §7,
 * greenhouse-company-domains spec §3): both group job references by a grouping
 * key (host for most sources, host + first path segment for Greenhouse), fetch
 * one detail page per key, and apply the resolved company link to every job of
 * that key. The response is keyed by the original job URL so callers never
 * re-derive hosts themselves.
 *
 * <p>At most {@code maxHosts} keys are resolved per call; overflow keys are
 * logged and skipped. Per-key failures (404/timeout/malformed) are non-fatal;
 * an implementation must not throw.
 */
public interface CompanyDomainResolverPort {

    /**
     * Reference to a job whose company website should be resolved. Carries the
     * career-page URL, the company name (used for name-affinity selection on
     * Greenhouse detail pages) and the source identifier (used to pick the
     * grouping key).
     */
    record JobRef(String url, String company, String source) {
    }

    /**
     * Resolve company websites for the given job references.
     *
     * @param jobs     career-page job references to group and resolve
     * @param maxHosts maximum number of distinct grouping keys resolved per call
     * @return map of job URL → normalized company-website URL (absolute, no
     *         trailing slash, tracking params stripped) for every URL whose key
     *         resolved; empty when nothing resolved or the input is empty
     */
    Map<String, String> resolveCompanyWebsites(List<JobRef> jobs, int maxHosts);

    // Note: a List<String> overload is impossible in Java (generic erasure), so
    // callers with only URLs build JobRefs (source="gupy", company=null) and call
    // the method above. See greenhouse-company-domains spec §3.1.
}