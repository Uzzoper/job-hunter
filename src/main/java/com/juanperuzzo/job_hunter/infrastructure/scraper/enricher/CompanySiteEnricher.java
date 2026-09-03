package com.juanperuzzo.job_hunter.infrastructure.scraper.enricher;

import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.domain.PortalDomains;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.EmailExtractor;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.UrlNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Enriches a {@link Job} with a contact email crawled from its company website,
 * following the email-enrichment spec P2 (Scenarios 12-16).
 * <p>
 * Policy: only runs when {@code contactEmail == null && companyWebsite != null};
 * crawls the origin then at most one contact path
 * ({@code /contato}, {@code /contact}, {@code /trabalhe-conosco}, {@code /carreiras}),
 * respecting {@code robots.txt}, rate-limited to {@code <=1 req/s per domain} via the
 * injected {@link RateLimiter} and bounded to {@code concurrency} simultaneous fetches
 * via a {@link Semaphore}. Results are cached per domain for 24h
 * (positive and negative entries). Failures are non-fatal: the job is returned unchanged.
 * <p>
 * Email extraction is delegated to the shared {@link EmailExtractor}; host/origin logic
 * matches the shared {@link PortalDomains} and {@link UrlNormalizer} constants.
 */
public class CompanySiteEnricher implements CompanySiteEnrichmentPort {

    private static final Logger log = LoggerFactory.getLogger(CompanySiteEnricher.class);

    private static final String USER_AGENT = "JobHunter/1.0";

    private final RestClient restClient;
    private final ExponentialBackoffRetry retry;
    private final RateLimiter rateLimiter;
    private final Semaphore semaphore;
    private final boolean enabled;
    private final int maxPages;
    private final Duration cacheTtl;
    private final List<String> contactPaths;
    private final Map<String, CachedContact> cache = new ConcurrentHashMap<>();

    public CompanySiteEnricher(
            RestClient restClient,
            ExponentialBackoffRetry retry,
            RateLimiter rateLimiter,
            boolean enabled,
            int concurrency,
            int maxPages,
            Duration cacheTtl,
            List<String> contactPaths) {
        this.restClient = restClient;
        this.retry = retry;
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
        this.semaphore = new Semaphore(concurrency > 0 ? concurrency : 2);
        this.maxPages = maxPages > 0 ? maxPages : 2;
        this.cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofHours(24);
        this.contactPaths = contactPaths != null ? contactPaths : List.of();
    }

    /**
     * Enrich a job with a company-site contact email. Never throws; returns the original
     * {@code job} reference when no enrichment is possible or applicable.
     */
    @Override
    public Job enrich(Job job) {
        if (job == null || !enabled) {
            return job;
        }
        // Scenario 12: skip crawl when the job already has a contact email or no website.
        if (job.contactEmail() != null || job.companyWebsite() == null) {
            return job;
        }

        var domain = host(job.companyWebsite());
        var origin = origin(job.companyWebsite());
        if (domain == null || origin == null) {
            log.debug("company site enrichment skipped for {}: invalid website {}", job.url(), job.companyWebsite());
            return job;
        }

        // Hotfix: skip job-portal domains that are not corporate sites.
        // Crawling these wastes ~5s per job (2 pages × 3 retries × backoff) and blocks
        // the synchronous fetch endpoint for hundreds of Gupy/InfoJobs listings.
        if (PortalDomains.isPortal(domain)) {
            log.debug("company site enrichment skipped for {}: portal domain {}", job.url(), domain);
            return job;
        }

        try {
            // Scenario 15: in-memory cache keyed by domain.
            var cached = cache.get(domain);
            if (cached != null && !isExpired(cached.fetchedAt())) {
                log.debug("company site cache hit for domain {}", domain);
                return cached.email() == null ? job : withEmail(job, cached.email());
            }

            var email = crawl(domain, origin, job.companyWebsite());
            cache.put(domain, new CachedContact(email, Instant.now()));

            if (email == null) {
                log.debug("no contact email found on company site {}", domain);
                return job;
            }
            log.info("enriched job {} with contact email {} from company site {}", job.url(), email, domain);
            return withEmail(job, email);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return job;
        }
    }

    /**
     * Crawl the homepage and (if needed) the first 200 contact path, returning the first
     * email found, or {@code null}. Conservative: robots.txt is checked per page, the
     * per-domain rate limiter is honored, and a semaphore bounds HTTP concurrency.
     *
     * <p>The homepage is fetched from {@code origin} and each contact path is joined
     * onto the {@code origin} (scheme + host + port), never onto the full
     * companyWebsite URL — otherwise a site hosted under a sub-path (e.g.
     * {@code example.com/careers}) would fetch {@code example.com/careers/contato}.
     */
    private String crawl(String domain, String origin, String baseUrl) throws InterruptedException {
        var robots = withRateLimit(domain, () -> fetchRobots(origin));
        var pagesFetched = 0;

        var paths = new ArrayList<String>();
        paths.add("");
        paths.addAll(contactPaths);

        for (var path : paths) {
            if (pagesFetched >= maxPages) {
                break;
            }
            if (!isAllowedByRobots(robots, path)) {
                log.debug("robots.txt disallows {} '{}', skipping", domain, path);
                continue;
            }
            semaphore.acquire();
            try {
                rateLimiter.acquire(domain);
                pagesFetched++;
                var pageUrl = path.isEmpty() ? origin : origin + path;
                var email = fetchAndExtract(pageUrl);
                if (email != null) {
                    return email;
                }
            } finally {
                semaphore.release();
            }
        }
        return null;
    }

    /** Fetch {@code {origin}/robots.txt}. Any failure (404/5xx/timeout) yields {@code null} = allow all. */
    private String fetchRobots(String origin) {
        var url = origin + "/robots.txt";
        try {
            return retry.execute(() -> fetchBody(url));
        } catch (Exception e) {
            log.debug("robots.txt unavailable for {}: {}", origin, e.getMessage());
            return null;
        }
    }

    /** Fetch a single page URL and extract an email from its HTML. Never throws. */
    private String fetchAndExtract(String url) {
        try {
            return retry.execute(() -> extractFromHtml(fetchBody(url)));
        } catch (Exception e) {
            log.warn("company site crawl failed for {}: {}", url, e.getMessage());
            return null;
        }
    }    private String fetchBody(String url) {
        var body = restClient.get()
                .uri(url)
                .header("User-Agent", USER_AGENT)
                .retrieve()
                .body(String.class);
        return body == null ? "" : body;
    }

    /**
     * Simple robots.txt check: a {@code Disallow: /} rules out every page, an exact
     * path match rules out that page. Missing/blank robots content allows everything.
     */
    private boolean isAllowedByRobots(String robotsContent, String path) {
        if (robotsContent == null || robotsContent.isBlank()) {
            return true;
        }
        var normalizedPath = path == null || path.isBlank() ? "/" : path;
        for (var rawLine : robotsContent.split("\n")) {
            var line = rawLine.trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("disallow")) {
                continue;
            }
            var colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            var value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            if (equalsPath(value, "/") || equalsPath(value, normalizedPath)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsPath(String robotsValue, String requestedPath) {
        var value = robotsValue.trim().toLowerCase(Locale.ROOT);
        var path = requestedPath.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return false;
        }
        if (path.isEmpty()) {
            path = "/";
        }
        // "Disallow: /" rules out every path.
        if (value.equals("/")) {
            return true;
        }
        var normalizedValue = value.replaceAll("/+$", "");
        var normalizedPath = path.replaceAll("/+$", "");
        return !normalizedValue.isEmpty() && normalizedValue.equals(normalizedPath);
    }

    /**
     * Extract a contact email from raw HTML using the shared {@link EmailExtractor}
     * priority: {@code a[href^=mailto:]} first, then a regex over the de-obfuscated
     * plain text, with the same filters (noreply/apply + placeholder domains).
     */
    private String extractFromHtml(String html) {
        return EmailExtractor.extractFromHtml(html);
    }

    private Job withEmail(Job job, String email) {
        return job.withContactEmail(email);
    }

    private boolean isExpired(Instant fetchedAt) {
        return Instant.now().isAfter(fetchedAt.plus(cacheTtl));
    }

    private static String host(String url) {
        try {
            var uri = URI.create(url);
            var host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** Scheme + host + port (no path) of the website, used to derive {@code /robots.txt}. */
    private static String origin(String url) {
        try {
            var uri = URI.create(url);
            var scheme = uri.getScheme();
            var host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            var port = uri.getPort();
            return port < 0 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /** Run a rate-limited, semaphore-bounded supplier (used for the robots.txt check). */
    private <T> T withRateLimit(String domain, Supplier<T> supplier) throws InterruptedException {
        semaphore.acquire();
        try {
            rateLimiter.acquire(domain);
            return supplier.get();
        } finally {
            semaphore.release();
        }
    }
}