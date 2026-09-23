package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Lever board provider ({@code providerId}: "lever") — reads the public,
 * no-auth Lever postings API: {@code GET /v0/postings/{site}?mode=json}.
 * The response is a top-level JSON array (no envelope), so the shared
 * {@link RestApiStrategy} is wired with an empty json path.
 *
 * <p>Sites are configured via {@code ats.lever-sites} and their company labels
 * via {@code ats.display-names} (Lever responses carry no company). Each site
 * is paginated with {@code skip}/{@code limit} (spec §4, default 100) and fetched
 * independently with its own try/catch so a dead (404) or rate-limited (429) site
 * is skipped with a log line and never fails the whole provider fetch. Jobs are
 * deduplicated by URL across pages and sites.
 */
public class LeverProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(LeverProvider.class);

    private final String providerId;
    private final ExponentialBackoffRetry retry;
    private final List<SiteStrategy> sites;
    private final int pageSize;
    private final int maxPages;

    /** Site + its dedicated strategy; the mapper closes over the site's display name. */
    private record SiteStrategy(String name, RestApiStrategy strategy) {}

    /**
     * Production constructor: wires one {@link RestApiStrategy} per site, each with a
     * mapper that closes over the site's company display name.
     *
     * @param displayNames company label per site token (unknown sites fall back to the token itself)
     * @param pageSize     pagination page size (spec: default 100)
     * @param maxPages     hard cap on pages fetched per site (mirrors InfoJobs' max-pages, default 3)
     */
    public LeverProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> sites,
            Map<String, String> displayNames,
            ExponentialBackoffRetry retry,
            int pageSize,
            int maxPages) {
        this.providerId = "lever";
        this.retry = retry;
        this.sites = sites.stream().map(site -> {
            var displayName = displayNames.getOrDefault(site, site);
            var strategy = new RestApiStrategy(providerId, baseUrl, timeoutSeconds, "", node -> mapNode(node, displayName));
            return new SiteStrategy(site, strategy);
        }).toList();
        this.pageSize = pageSize;
        this.maxPages = maxPages;
    }

    /**
     * Test-focused constructor allowing an injected {@link RestApiStrategy}
     * (e.g. pointing at a WireMock server) while keeping the site loop and
     * pagination logic.
     */
    public LeverProvider(
            String providerId,
            RestApiStrategy apiStrategy,
            ExponentialBackoffRetry retry,
            List<String> sites,
            Map<String, String> displayNames,
            int pageSize,
            int maxPages) {
        this.providerId = providerId;
        this.retry = retry;
        this.sites = sites.stream()
                .map(site -> new SiteStrategy(site, apiStrategy))
                .toList();
        this.pageSize = pageSize;
        this.maxPages = maxPages;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();
        log.info("{}: {} boards/sites configured", providerId, sites.size());

        for (var site : sites) {
            try {
                var jobs = fetchSite(site);
                for (var job : jobs) {
                    uniqueJobs.putIfAbsent(job.url(), job);
                }
                log.info("{}: fetched {} unique jobs for site '{}'", providerId, jobs.size(), site.name());
            } catch (Exception e) {
                // 404 (dead site), exhausted 429 retries or 5xx: log and skip this
                // site — one bad site never fails the provider fetch (spec §5).
                log.warn("{}: skipping site '{}' after failure: {}", providerId, site.name(), e.getMessage());
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        log.info("{}: total unique jobs fetched: {}", providerId, result.size());
        return result;
    }

    /**
     * Fetch one site page by page. The Lever API returns at most {@code pageSize}
     * jobs per call ({@code skip}/{@code limit}, default 100); loop while a page is
     * full, deduplicating by URL across pages. A short or empty page ends the loop.
     *
     * <p>Two guards (PR#84 review P2-a, mirroring InfoJobs's max-pages cap):
     * pagination stops after {@code maxPages} full pages, and stops immediately
     * when a full page yields zero <em>new</em> URLs (the API ignoring {@code skip}
     * and repeating the same page forever would otherwise loop without progress).
     */
    private List<RawJob> fetchSite(SiteStrategy site) {
        var allJobs = new ArrayList<RawJob>();
        var seenUrls = new HashSet<String>();
        int skip = 0;
        int pagesFetched = 0;

        while (pagesFetched < maxPages) {
            var path = "/v0/postings/" + site.name()
                    + "?mode=json&skip=" + skip + "&limit=" + pageSize;
            var page = retry.execute(() -> site.strategy().extractWithPath(path));

            int newUrls = 0;
            for (var job : page) {
                if (seenUrls.add(job.url())) {
                    allJobs.add(job);
                    newUrls++;
                }
            }
            pagesFetched++;

            if (page.size() < pageSize) {
                // Short or empty page → last page (normal termination).
                break;
            }
            if (newUrls == 0) {
                log.warn("{}: site '{}' page {} returned no new jobs (API ignoring skip) — stopping pagination",
                        providerId, site.name(), pagesFetched);
                break;
            }
            skip += pageSize;
        }

        if (pagesFetched >= maxPages) {
            log.warn("{}: site '{}' hit the max-pages cap ({}) — stopping pagination",
                    providerId, site.name(), maxPages);
        }

        return List.copyOf(allJobs);
    }

    /**
     * Map a single Lever posting node to a {@link RawJob}. Blank title or URL →
     * skip (returns null). Company is resolved from the configured display name
     * (Lever responses do not include it).
     */
    private RawJob mapNode(JsonNode node, String company) {
        var title = node.path("text").asText("");
        var url = node.path("hostedUrl").asText("");
        if (title.isBlank() || url.isBlank()) {
            return null;
        }

        // Lever createdAt is epoch millis — convert in UTC to yyyy-MM-dd (spec §4).
        var createdAt = node.path("createdAt").asLong(-1);
        var rawDate = createdAt >= 0
                ? Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString()
                : null;
        var workModel = inferWorkModel(node);

        return new RawJob(title, company, url, node.path("descriptionPlain").asText(null), rawDate,
                node.path("categories").path("location").asText(null), workModel, "lever", new HashMap<>());
    }

    /**
     * Lever exposes a lowercase {@code workplaceType}: {@code remote} → "Remoto",
     * {@code hybrid} → "Híbrido"; {@code on-site} / {@code unspecified} / unknown → null.
     */
    private static String inferWorkModel(JsonNode node) {
        var workplaceType = node.path("workplaceType").asText("").toLowerCase();
        if ("remote".equals(workplaceType)) {
            return "Remoto";
        }
        if ("hybrid".equals(workplaceType)) {
            return "Híbrido";
        }
        return null;
    }
}