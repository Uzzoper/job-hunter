package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.HtmlUtils;

import java.util.HashMap;
import java.util.List;

/**
 * Greenhouse board provider ({@code providerId}: "greenhouse") — reads the
 * public, no-auth Greenhouse board API:
 * {@code GET /v1/boards/{token}/jobs?content=true}.
 *
 * <p>Boards are configured via {@code ats.greenhouse-boards} (see
 * docs/specs/ats-provider.md). Each board token is fetched independently with
 * its own try/catch so a dead (404) or rate-limited (429) board is skipped
 * with a log line and never fails the whole provider fetch. Jobs are
 * deduplicated by URL across boards.
 */
public class GreenhouseProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseProvider.class);
    private static final String JSON_PATH = "jobs";

    private final String providerId;
    private final RestApiStrategy apiStrategy;
    private final ExponentialBackoffRetry retry;
    private final List<String> boardTokens;

    /**
     * Production constructor: wires the provider's own {@link #mapNode(JsonNode)}
     * mapper against the given base URL.
     */
    public GreenhouseProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> boardTokens,
            ExponentialBackoffRetry retry) {
        this.providerId = "greenhouse";
        this.boardTokens = boardTokens;
        this.retry = retry;
        this.apiStrategy = new RestApiStrategy(providerId, baseUrl, timeoutSeconds, JSON_PATH, this::mapNode);
    }

    /**
     * Test-focused constructor allowing an injected {@link RestApiStrategy}
     * (e.g. pointing at a WireMock server) while keeping the board loop logic.
     */
    public GreenhouseProvider(
            String providerId,
            RestApiStrategy apiStrategy,
            ExponentialBackoffRetry retry,
            List<String> boardTokens) {
        this.providerId = providerId;
        this.apiStrategy = apiStrategy;
        this.retry = retry;
        this.boardTokens = boardTokens;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();

        for (var boardToken : boardTokens) {
            try {
                var path = "/v1/boards/" + boardToken + "/jobs?content=true";
                var jobs = retry.execute(() -> apiStrategy.extractWithPath(path));

                for (var job : jobs) {
                    uniqueJobs.putIfAbsent(job.url(), job);
                }

                log.debug("{}: fetched {} jobs for board '{}'", providerId, jobs.size(), boardToken);
            } catch (Exception e) {
                // 404 (dead board), exhausted 429 retries or 5xx: log and skip this
                // board — one bad board never fails the provider fetch (spec §5).
                log.warn("{}: skipping board '{}' after failure: {}", providerId, boardToken, e.getMessage());
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        log.info("{}: total unique jobs fetched: {}", providerId, result.size());
        return result;
    }

    /**
     * Map a single Greenhouse job node to a {@link RawJob}. Blank title or URL →
     * skip (returns null). Greenhouse's {@code content} field is HTML double-encoded
     * ({@code &amp;lt;}), so it is unescaped twice and then tags are stripped.
     */
    private RawJob mapNode(JsonNode node) {
        var title = node.path("title").asText("");
        var url = node.path("absolute_url").asText("");
        if (title.isBlank() || url.isBlank()) {
            return null;
        }

        var company = stripSuffix(node.path("company_name").asText(""));
        var rawDate = node.path("first_published").asText(null);
        if (rawDate != null && rawDate.length() >= 10) {
            rawDate = rawDate.substring(0, 10);
        }
        var location = node.path("location").path("name").asText(null);
        var content = node.path("content").asText("");
        var description = content.isBlank() ? null : decodeHtmlTwice(content);
        var workModel = inferWorkModel(location, description);

        return new RawJob(title, company, url, description, rawDate, location,
                workModel, "greenhouse", new HashMap<>());
    }

    /** Strip a trailing {@code " - <suffix>"} from the company name (e.g. "Stone - Linkedin" → "Stone"). */
    private static String stripSuffix(String company) {
        if (company == null || company.isBlank()) {
            return company;
        }
        var index = company.indexOf(" - ");
        return index >= 0 ? company.substring(0, index).trim() : company;
    }

    /** Greenhouse content is HTML double-encoded: unescape twice, then strip tags (Jsoup). */
    private static String decodeHtmlTwice(String content) {
        var once = HtmlUtils.htmlUnescape(content);
        var twice = HtmlUtils.htmlUnescape(once);
        return Jsoup.parse(twice).text();
    }

    /**
     * Greenhouse has no explicit remote/hybrid flag — infer it from location and
     * description texts. {@code "Remoto"} wins over {@code "Híbrido"} when both
     * appear; never blocks on unknown (null workModel).
     */
    private static String inferWorkModel(String location, String description) {
        var text = ((location != null ? location : "") + " " + (description != null ? description : "")).toLowerCase();
        if (text.contains("remoto") || text.contains("remote")) {
            return "Remoto";
        }
        if (text.contains("hibrido") || text.contains("hibrida") || text.contains("hybrid")) {
            return "Híbrido";
        }
        return null;
    }
}