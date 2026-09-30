package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.PortalDomains;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.UrlNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

public class GupyProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GupyProvider.class);
    private static final String JSON_PATH = "data";
    private static final String DETAIL_RATE_LIMIT_KEY = "gupy-detail";

    private static final List<String> EXCLUDED_COMPANY_HOST_SUFFIXES = List.of(
            // Social / tracker hosts (gupy-detail-domains spec §3)
            "linkedin.com", "facebook.com", "instagram.com", "youtube.com",
            "twitter.com", "x.com", "whatsapp.com", "tiktok.com",
            // Asset / CDN hosts (google fonts/gstatic, CDNs)
            "googleapis.com", "gstatic.com", "cloudflare.com", "cloudfront.net",
            "fastly.net", "akamaihd.net", "jsdelivr.net", "unpkg.com");

    private final String providerId;
    private final RestApiStrategy apiStrategy;
    private final ExponentialBackoffRetry retry;
    private final List<String> keywords;
    private final int limit;
    private final RestClient detailRestClient;
    private final RateLimiter rateLimiter;
    private final int maxDetailDomains;

    public GupyProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int limit,
            ExponentialBackoffRetry retry) {
        this(baseUrl, timeoutSeconds, keywords, limit, retry, null, null, 0);
    }

    public GupyProvider(
            String providerId,
            RestApiStrategy apiStrategy,
            ExponentialBackoffRetry retry,
            List<String> keywords,
            int limit) {
        this(providerId, apiStrategy, retry, keywords, limit, null, null, 0);
    }

    public GupyProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int limit,
            ExponentialBackoffRetry retry,
            RestClient detailRestClient,
            RateLimiter rateLimiter,
            int maxDetailDomains) {
        this.providerId = "gupy";
        this.keywords = keywords;
        this.limit = limit;
        this.retry = retry;
        this.apiStrategy = new RestApiStrategy("gupy", baseUrl, timeoutSeconds, JSON_PATH, this::mapNode);
        this.detailRestClient = detailRestClient;
        this.rateLimiter = rateLimiter;
        this.maxDetailDomains = maxDetailDomains;
    }

    public GupyProvider(
            String providerId,
            RestApiStrategy apiStrategy,
            ExponentialBackoffRetry retry,
            List<String> keywords,
            int limit,
            RestClient detailRestClient,
            RateLimiter rateLimiter,
            int maxDetailDomains) {
        this.providerId = providerId;
        this.apiStrategy = apiStrategy;
        this.retry = retry;
        this.keywords = keywords;
        this.limit = limit;
        this.detailRestClient = detailRestClient;
        this.rateLimiter = rateLimiter;
        this.maxDetailDomains = maxDetailDomains;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();
        var authFailed = false;

        for (var keyword : keywords) {
            if (authFailed) {
                break;
            }
            try {
                var path = "/api/v1/jobs?jobName=" + urlEncode(keyword) + "&limit=" + limit;
                var jobs = retry.execute(() -> apiStrategy.extractWithPath(path));

                for (var job : jobs) {
                    uniqueJobs.putIfAbsent(job.url(), job);
                }

                log.debug("{}: fetched {} jobs for keyword '{}'", providerId, jobs.size(), keyword);
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    log.warn("{}: auth failure (401/403) on keyword '{}', skipping remaining keywords",
                            providerId, keyword);
                    authFailed = true;
                } else {
                    log.error("{}: failed to fetch keyword '{}': {}", providerId, keyword, e.getMessage());
                }
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        var resolved = resolveCompanyDomains(result);
        log.info("{}: total unique jobs fetched: {}", providerId, resolved.size());
        return resolved;
    }

    /**
     * Resolve a company website per career-page host and attach it as
     * {@code companyWebsite} metadata to every job of that host
     * (gupy-detail-domains spec, issue #74).
     *
     * <p>The domain is per company, not per job: jobs are grouped by the host of
     * their listing URL (first-seen order), exactly one detail page per host is
     * fetched (the first job's URL), and the first eligible company link from that
     * page is reused for all jobs of the same host — hundreds of hosts, not
     * thousands of fetches. At most {@code maxDetailDomains} hosts are resolved per
     * fetch; overflow hosts are logged and skipped.
     *
     * <p>Per-host failures (404/timeout/malformed) are non-fatal: the host is
     * skipped with a warning and its jobs keep their list-level metadata — a host
     * failure never fails the provider fetch. When detail resolution is disabled
     * (no detail {@link RestClient} or no positive cap) the jobs are returned
     * unchanged.
     */
    private List<RawJob> resolveCompanyDomains(List<RawJob> jobs) {
        if (jobs.isEmpty() || detailRestClient == null || rateLimiter == null || maxDetailDomains <= 0) {
            return jobs;
        }

        var hostToJobUrl = new LinkedHashMap<String, String>();
        for (var job : jobs) {
            var host = UrlNormalizer.host(job.url());
            if (host == null || host.isBlank() || hostToJobUrl.containsKey(host)) {
                continue;
            }
            hostToJobUrl.put(host, job.url());
        }
        if (hostToJobUrl.isEmpty()) {
            return jobs;
        }

        var hosts = new ArrayList<>(hostToJobUrl.entrySet());
        var resolvedWebsites = new HashMap<String, String>();
        for (int i = 0; i < hosts.size(); i++) {
            if (i >= maxDetailDomains) {
                log.warn("{}: detail-domain cap {} reached, skipping {} remaining host(s)",
                        providerId, maxDetailDomains, hosts.size() - i);
                break;
            }
            var host = hosts.get(i).getKey();
            var jobUrl = hosts.get(i).getValue();
            try {
                var website = extractCompanyWebsite(fetchDetailHtml(jobUrl), host);
                if (website != null) {
                    resolvedWebsites.put(host, website);
                }
                log.debug("{}: resolved companyWebsite {} for host {}", providerId, website, host);
            } catch (Exception e) {
                log.warn("{}: detail page failed for host {} ({}), skipping host", providerId, host, e.getMessage());
            }
        }

        if (resolvedWebsites.isEmpty()) {
            return jobs;
        }

        var results = new ArrayList<RawJob>(jobs.size());
        for (var job : jobs) {
            var host = UrlNormalizer.host(job.url());
            var website = host != null ? resolvedWebsites.get(host) : null;
            results.add(website == null ? job : withCompanyWebsite(job, website));
        }
        return results;
    }

    /** Rate-limited, retried detail-page fetch (shared retry + rate limiter, key {@value #DETAIL_RATE_LIMIT_KEY}). */
    private String fetchDetailHtml(String jobUrl) {
        rateLimiter.acquire(DETAIL_RATE_LIMIT_KEY);
        return retry.execute(() -> {
            var body = detailRestClient.get()
                    .uri(jobUrl)
                    .retrieve()
                    .body(String.class);
            return body == null ? "" : body;
        });
    }

    /**
     * First eligible company link on a Gupy detail page (gupy-detail-domains spec §3).
     * Collects {@code href="http(s)://..."} anchors in document order and keeps the
     * first whose host is not the portal host itself, any {@code *gupy.*} host, a
     * social/tracker host, or an asset/CDN host. Returns the trailing-slash-normalized
     * full link URL — the stored {@code companyWebsite} stays an absolute URL so
     * {@code JobNormalizer} and {@code CompanySiteEnricher} consume it unchanged — or
     * null when no eligible link exists.
     */
    private static String extractCompanyWebsite(String html, String fetchedHost) {
        if (html == null || html.isBlank()) {
            return null;
        }
        var doc = Jsoup.parse(html);
        for (var anchor : doc.select("a[href]")) {
            var href = anchor.absUrl("href");
            if (href.isBlank() || !(href.startsWith("http://") || href.startsWith("https://"))) {
                continue;
            }
            var host = UrlNormalizer.host(href);
            if (host == null || isExcludedCompanyLinkHost(host, fetchedHost)) {
                continue;
            }
            return UrlNormalizer.noTrailingSlash(href);
        }
        return null;
    }

    /**
     * Link-policy eligibility: true when the link host must never be stored as a
     * company site — the portal host itself, any job-portal suffix (gupy/infojobs/
     * vaga-ja via {@link PortalDomains}), and the social/tracker + asset/CDN hosts.
     */
    private static boolean isExcludedCompanyLinkHost(String host, String fetchedHost) {
        if (fetchedHost != null && host.equals(fetchedHost)) {
            return true;
        }
        if (PortalDomains.isPortal(host)) {
            return true;
        }
        return EXCLUDED_COMPANY_HOST_SUFFIXES.stream()
                .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
    }

    private static RawJob withCompanyWebsite(RawJob job, String website) {
        var metadata = new HashMap<>(job.metadata());
        metadata.put("companyWebsite", website);
        return new RawJob(
                job.title(), job.company(), job.url(), job.description(),
                job.rawDate(), job.location(), job.workModel(), job.source(), metadata);
    }

    /**
     * Detect an authentication failure (HTTP 401/403) by inspecting the actual HTTP
     * status of any {@link RestClientResponseException} in the exception cause chain,
     * rather than matching on a fragile substring of the exception message.
     */
    private static boolean isAuthFailure(Exception e) {
        for (var current = (Throwable) e; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException rce) {
                int code = rce.getStatusCode().value();
                if (code == 401 || code == 403) {
                    return true;
                }
            }
        }
        return false;
    }

    private RawJob mapNode(JsonNode node) {
        var title = node.path("name").asText("");
        var url = getJobUrl(node);
        if (title.isBlank() || url.isBlank()) {
            return null;
        }
        var rawDate = node.path("publishedDate").asText(null);
        if (rawDate != null && rawDate.length() >= 10) {
            rawDate = rawDate.substring(0, 10);
        }
        var location = node.path("city").asText(null);
        var state = node.path("state").asText(null);
        var locationStr = location != null
                ? (state != null ? location + ", " + state : location)
                : state;
        var isRemote = node.path("isRemoteWork").asBoolean(false);
        var workModel = isRemote ? "Remoto" : null;
        var company = node.path("careerPageName").asText(null);

        // Scenario 10: the list API has no real company-website field — careerPageUrl is
        // always a Gupy-hosted portal page (e.g. https://techco.gupy.io). Portal URLs are
        // never stored as companyWebsite (null instead); real-site extraction happens in
        // resolveCompanyDomains(), which fetches one detail page per host (#74).
        var metadata = new HashMap<String, String>();
        var careerPageUrl = node.path("careerPageUrl").asText("");
        if (!careerPageUrl.isBlank() && !isPortalUrl(careerPageUrl)) {
            metadata.put("companyWebsite", UrlNormalizer.noTrailingSlash(careerPageUrl));
        }

        return new RawJob(
                title,
                company,
                url,
                node.path("description").asText(null),
                rawDate,
                locationStr,
                workModel,
                "gupy",
                metadata);
    }

    private static String getJobUrl(JsonNode node) {
        if (node.has("jobUrl")) {
            var jobUrl = node.path("jobUrl").asText("");
            if (!jobUrl.isBlank()) return jobUrl;
        }
        return node.path("careerPageUrl").asText("");
    }

    /** True when the given URL host ends with a known job-portal suffix (see {@link PortalDomains}). */
    private static boolean isPortalUrl(String url) {
        var host = UrlNormalizer.host(url);
        return host != null && PortalDomains.isPortal(host);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
