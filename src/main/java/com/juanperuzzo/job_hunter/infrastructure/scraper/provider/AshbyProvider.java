package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ashby board provider ({@code providerId}: "ashby") — reads the public,
 * no-auth Ashby posting API: {@code GET /posting-api/job-board/{board}}.
 * The response is a top-level JSON array (no envelope), so the shared
 * {@link RestApiStrategy} is wired with an empty json path.
 *
 * <p>Boards are configured via {@code ats.ashby-boards} and their company
 * labels via {@code ats.display-names} (Ashby responses carry no company). Each
 * board is fetched independently with its own try/catch so a dead (404) or
 * rate-limited (429) board is skipped with a log line and never fails the whole
 * provider fetch. Jobs are deduplicated by URL across boards. An
 * {@code employmentType: "Intern"} is forwarded in {@code atsEmploymentType}
 * metadata as a junior hint for the normalizer (docs/specs/ats-provider.md).
 */
public class AshbyProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(AshbyProvider.class);

    private final String providerId;
    private final ExponentialBackoffRetry retry;
    private final List<BoardStrategy> boards;

    /** Board + its dedicated strategy; the mapper closes over the board's display name. */
    private record BoardStrategy(String name, RestApiStrategy strategy) {}

    /**
     * Production constructor: wires one {@link RestApiStrategy} per board, each with a
     * mapper that closes over the board's company display name.
     *
     * @param displayNames company label per board token (unknown boards fall back to the token itself)
     */
    public AshbyProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> boardNames,
            Map<String, String> displayNames,
            ExponentialBackoffRetry retry) {
        this.providerId = "ashby";
        this.retry = retry;
        this.boards = boardNames.stream().map(board -> {
            var displayName = displayNames.getOrDefault(board, board);
            var strategy = new RestApiStrategy(providerId, baseUrl, timeoutSeconds, "", node -> mapNode(node, displayName));
            return new BoardStrategy(board, strategy);
        }).toList();
    }

    /**
     * Test-focused constructor allowing an injected {@link RestApiStrategy}
     * (e.g. pointing at a WireMock server) while keeping the board loop logic.
     */
    public AshbyProvider(
            String providerId,
            RestApiStrategy apiStrategy,
            ExponentialBackoffRetry retry,
            List<String> boardNames,
            Map<String, String> displayNames) {
        this.providerId = providerId;
        this.retry = retry;
        this.boards = boardNames.stream()
                .map(board -> new BoardStrategy(board, apiStrategy))
                .toList();
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();

        for (var board : boards) {
            try {
                var path = "/posting-api/job-board/" + board.name();
                var jobs = retry.execute(() -> board.strategy().extractWithPath(path));

                for (var job : jobs) {
                    uniqueJobs.putIfAbsent(job.url(), job);
                }

                log.debug("{}: fetched {} jobs for board '{}'", providerId, jobs.size(), board.name());
            } catch (Exception e) {
                // 404 (dead board), exhausted 429 retries or 5xx: log and skip this
                // board — one bad board never fails the provider fetch (spec §5).
                log.warn("{}: skipping board '{}' after failure: {}", providerId, board.name(), e.getMessage());
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        log.info("{}: total unique jobs fetched: {}", providerId, result.size());
        return result;
    }

    /**
     * Map a single Ashby job node to a {@link RawJob}. Blank title or URL → skip
     * (returns null). Company is resolved from the configured display name
     * (Ashby responses do not include it).
     */
    private RawJob mapNode(JsonNode node, String company) {
        var title = node.path("title").asText("");
        var url = node.path("jobUrl").asText("");
        if (title.isBlank() || url.isBlank()) {
            return null;
        }

        var rawDate = node.path("publishedAt").asText(null);
        if (rawDate != null && rawDate.length() >= 10) {
            rawDate = rawDate.substring(0, 10);
        }
        var workModel = inferWorkModel(node);
        var metadata = new HashMap<String, String>();
        var employmentType = node.path("employmentType").asText("");
        if ("Intern".equalsIgnoreCase(employmentType)) {
            metadata.put("atsEmploymentType", employmentType);
        }

        return new RawJob(title, company, url, node.path("descriptionPlain").asText(null), rawDate,
                node.path("location").asText(null), workModel, "ashby", metadata);
    }

    /**
     * Ashby exposes both an {@code isRemote} flag and a {@code workplaceType}
     * field ({@code Remote | Hybrid | OnSite | ...}): true flag or Remote type →
     * "Remoto"; Hybrid type → "Híbrido"; anything else (including unknown) → null.
     */
    private static String inferWorkModel(JsonNode node) {
        var isRemote = node.path("isRemote").asBoolean(false);
        var workplaceType = node.path("workplaceType").asText("");
        if (isRemote || "Remote".equalsIgnoreCase(workplaceType)) {
            return "Remoto";
        }
        if ("Hybrid".equalsIgnoreCase(workplaceType)) {
            return "Híbrido";
        }
        return null;
    }
}