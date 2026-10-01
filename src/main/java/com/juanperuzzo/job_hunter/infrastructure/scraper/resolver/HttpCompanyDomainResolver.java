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
import java.util.Set;

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
     * lookalikes such as {@code next.com.br} survive. Extend as new tokens appear
     * in production data.
     */
    private static final List<String> TECH_TOKEN_HOSTS = List.of(
            "node.js", "react.js", "angular.js", "vue.js", "next.js", "nuxt.js",
            "svelte.js", "ember.js", "backbone.js", "jquery.js", "axios.js",
            "lodash.js", "redux.js", "webpack.js", "gulp.js", "grunt.js",
            "babel.js", "typescript.js", "watson.data", "asp.net");

    /**
     * TLDs that are file or technology extensions and therefore never a company
     * site — they pass the alphabetic-TLD rule while being document/code paths
     * (link-quality v3: production pollution from {@code gera.Java} and
     * {@code ASP.NET}). Curated, not exhaustive: only extensions whose name is not
     * a real TLD are listed.
     *
     * <p>Only extensions whose name is NOT a delegated TLD are listed. {@code net}
     * (gTLD), {@code zip} (Google gTLD) and {@code md} (Moldova ccTLD) were
     * removed after a review found they silently rejected legitimate company
     * sites; {@code cs} (retired Czechoslovakia/Serbia-Montenegro code, not
     * currently delegated) was removed on the same grounds. The {@code ASP.NET}
     * pollution is handled by the {@value #TECH_TOKEN_HOSTS} entry instead.
     *
     * <p>Deliberate remaining ccTLD tradeoffs: {@code py} (Paraguay) and {@code sh}
     * (Saint Helena) are assigned TLDs, but in a job posting a link to
     * {@code empresa.py} or {@code empresa.sh} is a script, not a recruiter — and
     * failing closed only costs an unresolvable row, never a wrong company site.
     * Real TLDs that merely sound technical are intentionally absent: {@code io},
     * {@code ai}, {@code co}, {@code dev}, {@code app}, {@code tech}, {@code data},
     * {@code cs}, {@code md}. Add entries as new pollution appears, but verify the
     * extension is not a delegated TLD first.
     */
    private static final Set<String> FILE_EXTENSION_TLDS = Set.of(
            // Markup / styles / data / config documents
            "html", "htm", "css", "scss", "xml", "json", "yaml", "yml", "toml",
            "ini", "cfg", "conf", "sql", "log", "csv",
            // Source code
            "js", "jsx", "ts", "tsx", "java", "jsp", "asp", "aspx", "php",
            "py", "rb", "go", "sh", "bat", "ps1", "ipynb",
            // Binary documents / assets
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rar", "tar",
            "gz", "exe", "dll", "apk", "dmg", "png", "jpg", "jpeg", "gif", "svg",
            "ico", "webp", "woff", "woff2", "ttf", "eot");

    /**
     * URL-shortener hosts: real domains with alphabetic TLDs, so neither the TLD
     * rule nor the tech-token list catches them, yet they are never a company site
     * (link-quality v3: production pollution from {@code bit.ly/xyz}). Matched on
     * label boundaries, subdomains included.
     */
    private static final Set<String> SHORTENER_HOSTS = Set.of(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
            "cutt.ly", "tiny.cc", "shorturl.at", "rebrand.ly", "lnkd.in",
            "youtu.be", "t.ly", "rb.gy", "s.id");

    /** Minimum length of an acceptable TLD ({@code .c} is never a company site). */
    private static final int MIN_TLD_LENGTH = 2;

    /**
     * Exact query-param names dropped before storing a company link (matched
     * case-insensitively); {@code utm_*} is dropped by prefix instead.
     */
    private static final List<String> TRACKING_PARAM_NAMES = List.of(
            "gclid", "gad", "fbclid", "msclkid");

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
     * alphabetic TLD of length {@value #MIN_TLD_LENGTH}+ that is not a file/tech
     * extension, and not be a tech-token host ({@value #TECH_TOKEN_HOSTS} — the
     * {@code Node.js}/{@code React.js} case where dot-presence is not a validity
     * signal, or {@code gera.Java}/{@code ASP.NET}, whose TLDs are extensions).
     * {@code asp.net} lives here and not in {@link #FILE_EXTENSION_TLDS} because
     * {@code net} IS a delegated gTLD — rejecting the whole TLD would silently drop
     * legitimate company sites while this exact host keeps the motivating case
     * covered. The host must additionally not be the portal host itself, any
     * job-portal or URL-shortener host, nor a social/tracker/asset host.
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
     * v2/v3): a dot, a purely alphabetic TLD of length {@value #MIN_TLD_LENGTH}+
     * that is not a file/tech extension, and no tech-token host. Fails closed — a
     * host that fails any check is skipped, and the next eligible link on the page
     * is considered instead.
     *
     * @param host lowercase host as returned by {@link UrlNormalizer#host(String)}
     */
    private static boolean isEligibleCompanyHost(String host) {
        var lastDot = host.lastIndexOf('.');
        if (lastDot <= 0) {
            return false;
        }
        var tld = host.substring(lastDot + 1);
        if (tld.length() < MIN_TLD_LENGTH || !isAlphabetic(tld) || FILE_EXTENSION_TLDS.contains(tld)) {
            return false;
        }
        return TECH_TOKEN_HOSTS.stream().noneMatch(token -> matchesHostOrSubdomain(host, token));
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
     * vaga-ja via {@link PortalDomains}), a URL shortener, and the social/tracker +
     * asset/CDN hosts.
     */
    private static boolean isExcludedCompanyLinkHost(String host, String fetchedHost) {
        if (fetchedHost != null && host.equals(fetchedHost)) {
            return true;
        }
        if (PortalDomains.isPortal(host)) {
            return true;
        }
        if (SHORTENER_HOSTS.stream().anyMatch(token -> matchesHostOrSubdomain(host, token))) {
            return true;
        }
        return EXCLUDED_COMPANY_HOST_SUFFIXES.stream()
                .anyMatch(suffix -> matchesHostOrSubdomain(host, suffix));
    }

    /**
     * Label-boundary host match: the host itself or any subdomain of it. Never a
     * bare suffix, so {@code st.co} is not a {@code t.co} subdomain. (A host
     * like {@code myvue.js} also survives this check in isolation, but the TLD
     * rule rejects {@code .js} hosts downstream regardless.)
     */
    private static boolean matchesHostOrSubdomain(String host, String token) {
        return host.equals(token) || host.endsWith("." + token);
    }

    /**
     * Drop ad-tracking query params ({@code gclid}, {@code gad}, {@code fbclid},
     * {@code msclkid} and {@code utm_*}, matched case-insensitively) from a URL
     * before storing it; every other param is preserved in its original order.
     * Returns the URL unchanged when it has no query string.
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
            if (isTrackingParam(name)) {
                continue;
            }
            kept.add(pair);
        }
        return kept.isEmpty() ? base : base + "?" + String.join("&", kept);
    }

    private static boolean isTrackingParam(String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("utm_") || TRACKING_PARAM_NAMES.contains(lower);
    }
}