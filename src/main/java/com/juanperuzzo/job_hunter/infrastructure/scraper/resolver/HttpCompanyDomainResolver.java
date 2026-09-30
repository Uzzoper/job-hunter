package com.juanperuzzo.job_hunter.infrastructure.scraper.resolver;

import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.domain.PortalDomains;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.UrlNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP-backed {@link CompanyDomainResolverPort}: resolves a company website per
 * career-page host by fetching one job detail page per host and reusing the
 * first eligible company link for every job of that host (gupy-detail-domains
 * spec §2/§3/§7).
 *
 * <p>The caller only supplies job URLs: grouping (first-seen host order) and
 * host→URL mapping are fully owned here, so the Gupy fetch path and the
 * company-website backfill share the exact same resolution code with zero
 * duplication. The response is keyed by the original job URL — callers never
 * re-derive hosts.
 *
 * <p>At most {@code maxHosts} hosts are resolved per call; overflow hosts are
 * logged and skipped. Per-host failures (404/timeout/malformed) are non-fatal:
 * the host is skipped with a warning and the remaining hosts still resolve.
 * Never throws.
 */
public class HttpCompanyDomainResolver implements CompanyDomainResolverPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCompanyDomainResolver.class);
    private static final String DETAIL_RATE_LIMIT_KEY = "gupy-detail";

    private static final List<String> EXCLUDED_COMPANY_HOST_SUFFIXES = List.of(
            // Social / tracker hosts (gupy-detail-domains spec §3)
            "linkedin.com", "facebook.com", "instagram.com", "youtube.com",
            "twitter.com", "x.com", "whatsapp.com", "tiktok.com",
            // Asset / CDN hosts (google fonts/gstatic, CDNs)
            "googleapis.com", "gstatic.com", "cloudflare.com", "cloudfront.net",
            "fastly.net", "akamaihd.net", "jsdelivr.net", "unpkg.com");

    /**
     * Tech-token hosts polluted production rows in 2026-09-30: detail pages link
     * to framework/tool documentation ({@code Node.js}, {@code React.js},
     * {@code watson.data} — 53 rows), and every one of them contains dots, so the
     * dot-presence test alone cannot filter them. Matched on label boundaries
     * (host equals the token or is a subdomain of it), never as a bare suffix, so
     * lookalikes such as {@code myvue.js} or {@code next.com.br} survive.
     * Extend as new tokens appear in production data.
     */
    private static final List<String> TECH_TOKEN_HOSTS = List.of(
            "node.js", "react.js", "angular.js", "vue.js", "next.js", "nuxt.js",
            "svelte.js", "ember.js", "backbone.js", "jquery.js", "axios.js",
            "lodash.js", "redux.js", "webpack.js", "gulp.js", "grunt.js",
            "babel.js", "typescript.js", "watson.data");

    /** Minimum length of an acceptable TLD ({@code .c} is never a company site). */
    private static final int MIN_TLD_LENGTH = 2;

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
        if (jobUrls == null || jobUrls.isEmpty() || maxHosts <= 0) {
            return Map.of();
        }

        // First-seen host order: the first URL of a host is the one fetched for it.
        var hostToJobUrls = new LinkedHashMap<String, List<String>>();
        for (var jobUrl : jobUrls) {
            if (jobUrl == null) {
                continue;
            }
            var host = UrlNormalizer.host(jobUrl);
            if (host == null || host.isBlank()) {
                continue;
            }
            hostToJobUrls.computeIfAbsent(host, h -> new ArrayList<>()).add(jobUrl);
        }
        if (hostToJobUrls.isEmpty()) {
            return Map.of();
        }

        var hosts = new ArrayList<>(hostToJobUrls.entrySet());
        var websitesByUrl = new HashMap<String, String>();
        for (int i = 0; i < hosts.size(); i++) {
            if (i >= maxHosts) {
                log.warn("company-domain cap {} reached, skipping {} remaining host(s)",
                        maxHosts, hosts.size() - i);
                break;
            }
            var host = hosts.get(i).getKey();
            var jobUrlsOfHost = hosts.get(i).getValue();
            try {
                var website = extractCompanyWebsite(fetchDetailHtml(jobUrlsOfHost.get(0)), host);
                if (website != null) {
                    for (var jobUrl : jobUrlsOfHost) {
                        websitesByUrl.put(jobUrl, website);
                    }
                }
                log.debug("resolved companyWebsite {} for host {}", website, host);
            } catch (Exception e) {
                log.warn("detail page failed for host {} ({}), skipping host", host, e.getMessage());
            }
        }

        return websitesByUrl;
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
     * First eligible company link on a detail page (gupy-detail-domains spec §3+
     * §7 link-quality fixes). Collects {@code href="http(s)://..."} anchors in
     * document order and keeps the first eligible one.
     * <p>
     * Eligibility is decided by the {@code host}: it must carry a dot, end in an
     * alphabetic TLD of length {@value #MIN_TLD_LENGTH}+, and not be a tech-token
     * host ({@value #TECH_TOKEN_HOSTS} — the {@code Node.js}/{@code React.js} case
     * where dot-presence is not a validity signal). The host must additionally not
     * be the portal host itself nor any job-portal/social/tracker/asset host.
     * Tracking query params ({@code gclid}/{@code utm_*}) are stripped before the
     * trailing-slash normalization. Returns the full link URL — the stored
     * {@code companyWebsite} stays an absolute URL so {@code JobNormalizer} and
     * {@code CompanySiteEnricher} consume it unchanged — or null when no eligible
     * link exists.
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
            if (host == null || !isEligibleCompanyHost(host) || isExcludedCompanyLinkHost(host, fetchedHost)) {
                continue;
            }
            return UrlNormalizer.noTrailingSlash(stripTrackingParams(href));
        }
        return null;
    }

    /**
     * Host validity for a company link (gupy-detail-domains spec §3, link-quality
     * v2): a dot, a purely alphabetic TLD of length {@value #MIN_TLD_LENGTH}+, and
     * no tech-token host. Fails closed — a host that fails any check is skipped,
     * and the next eligible link on the page is considered instead.
     *
     * @param host lowercase host as returned by {@link UrlNormalizer#host(String)}
     */
    private static boolean isEligibleCompanyHost(String host) {
        var lastDot = host.lastIndexOf('.');
        if (lastDot <= 0) {
            return false;
        }
        var tld = host.substring(lastDot + 1);
        if (tld.length() < MIN_TLD_LENGTH || !isAlphabetic(tld)) {
            return false;
        }
        return TECH_TOKEN_HOSTS.stream().noneMatch(token -> host.equals(token) || host.endsWith("." + token));
    }

    private static boolean isAlphabetic(String value) {
        for (int i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return !value.isEmpty();
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

    /**
     * Drop Google Ads ({@code gclid}) and UTM ({@code utm_*}) query params from a
     * URL before storing it; non-tracking params are preserved. Returns the URL
     * unchanged when it has no query string.
     */
    private static String stripTrackingParams(String url) {
        var queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url;
        }
        var base = url.substring(0, queryStart);
        var kept = new ArrayList<String>();
        for (var pair : url.substring(queryStart + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            var eq = pair.indexOf('=');
            var name = eq < 0 ? pair : pair.substring(0, eq);
            var lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("gclid") || lower.startsWith("utm_")) {
                continue;
            }
            kept.add(pair);
        }
        return kept.isEmpty() ? base : base + "?" + String.join("&", kept);
    }
}