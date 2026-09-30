package com.juanperuzzo.job_hunter.infrastructure.scraper.resolver;

import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * HTTP-backed {@link CompanyDomainResolverPort} (implementation in progress).
 */
public class HttpCompanyDomainResolver implements CompanyDomainResolverPort {

    private final RestClient detailRestClient;
    private final ExponentialBackoffRetry retry;
    private final RateLimiter rateLimiter;

    public HttpCompanyDomainResolver(
            RestClient detailRestClient,
            ExponentialBackoffRetry retry,
            RateLimiter rateLimiter) {
        this.detailRestClient = detailRestClient;
        this.retry = retry;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Map<String, String> resolveCompanyWebsites(List<String> jobUrls, int maxHosts) {
        return Map.of();
    }
}