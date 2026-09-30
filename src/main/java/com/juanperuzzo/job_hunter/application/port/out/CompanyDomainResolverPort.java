package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;
import java.util.Map;

/**
 * Resolves a company website URL per career-page host from job detail pages.
 *
 * <p>Shared by the Gupy fetch path and the company-website backfill
 * (gupy-detail-domains spec §7): both group job URLs by host, fetch one detail
 * page per host (the first job URL of each host), and apply the first eligible
 * company link to every job of that host. The response is keyed by the original
 * job URL so callers never re-derive hosts themselves.
 *
 * <p>At most {@code maxHosts} hosts are resolved per call; overflow hosts are
 * logged and skipped. Per-host failures (404/timeout/malformed) are non-fatal;
 * an implementation must not throw.
 */
public interface CompanyDomainResolverPort {

    /**
     * Resolve company websites for the given job URLs.
     *
     * @param jobUrls  career-page job URLs to group and resolve by host
     * @param maxHosts maximum number of distinct hosts resolved per call
     * @return map of job URL → normalized company-website URL (absolute, no
     *         trailing slash, tracking params stripped) for every URL whose
     *         host resolved; empty when nothing resolved or the input is empty
     */
    Map<String, String> resolveCompanyWebsites(List<String> jobUrls, int maxHosts);
}