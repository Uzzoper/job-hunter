package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientResponseException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GitHub "vagas" board provider ({@code providerId}: "github") — reads the public,
 * no-auth GitHub Issues REST API for community job boards such as
 * {@code frontendbr/vagas} and {@code backend-br/vagas}
 * (docs/specs/github-vagas.md): {@code GET /repos/{owner}/{repo}/issues?state=open&per_page=100}.
 * One open issue is one job; pull requests carry a {@code pull_request} key and are skipped.
 *
 * <p>Follows the same multi-board pattern as the ATS providers: a constructor-injected
 * {@link RestApiStrategy} + {@link ExponentialBackoffRetry}, a per-repo loop with one
 * try/catch per repo (a dead/rate-limited repo is skipped with a log line and never fails
 * the whole fetch), URL-keyed dedupe across repos, and RFC 5988 {@code Link: rel="next"}
 * pagination with a hard page cap. GitHub requires {@code Accept: application/vnd.github+json}
 * and a {@code User-Agent} header — both set as strategy default headers.
 *
 * <p>The company is parsed best-effort from the title suffix after the last
 * {@code " - "} / {@code " na "} / {@code " @ "} separator (null when absent); the raw
 * body is kept verbatim so {@code EmailExtractor} downstream can find application emails.
 */
public class GithubVagasProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GithubVagasProvider.class);

    private static final String PER_PAGE = "100";

    /** Hard cap on Link-followed pages per repo (same loop discipline as Lever pagination). */
    private static final int MAX_PAGES_PER_REPO = 10;

    private static final Map<String, String> GITHUB_HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "User-Agent", "JobHunter/1.0");

    private static final List<String> COMPANY_SEPARATORS = List.of(" - ", " na ", " @ ");

    /**
     * Labels carrying contract/flexibility/seniority signals (CLT/PJ, special role
     * levels) — never a location (spec §4). Everything else ("Remoto", "Híbrido",
     * "São Paulo", …) is treated as a location label, matching how the observed
     * boards label their postings (docs/specs/github-vagas.md §1).
     */
    private static final Set<String> NON_LOCATION_LABELS = Set.of(
            "clt", "pj", "senior", "pleno", "especialista", "estagio", "junior", "jr");

    private final String providerId;
    private final RestApiStrategy apiStrategy;
    private final ExponentialBackoffRetry retry;
    private final List<String> repos;

    public GithubVagasProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> repos,
            ExponentialBackoffRetry retry) {
        this.providerId = "github";
        this.repos = repos;
        this.retry = retry;
        this.apiStrategy = new RestApiStrategy(
                providerId, baseUrl, timeoutSeconds, "", this::mapNode, GITHUB_HEADERS);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();
        log.info("{}: {} repos configured", providerId, repos.size());

        for (var repo : repos) {
            try {
                var jobs = fetchRepo(repo);
                if (jobs.isEmpty()) {
                    log.warn("{}: repo '{}' returned no jobs", providerId, repo);
                } else {
                    log.info("{}: fetched {} jobs for repo '{}'", providerId, jobs.size(), repo);
                }
                for (var job : jobs) {
                    uniqueJobs.putIfAbsent(job.url(), job);
                }
            } catch (Exception e) {
                if (isNotFound(e)) {
                    log.warn("{}: repo '{}' not found (404), skipping", providerId, repo);
                } else {
                    log.error("{}: failed to fetch repo '{}': {}", providerId, repo, e.getMessage());
                }
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        log.info("{}: total unique jobs fetched: {}", providerId, result.size());
        return result;
    }

    /**
     * Fetches one repo following RFC 5988 {@code Link: rel="next"} pages while present.
     * Stops early when a page yields zero jobs (no progress) and hard-caps the number of
     * pages so a hostile/duplicated Link chain can never loop forever.
     */
    private List<RawJob> fetchRepo(String repo) {
        var jobs = new ArrayList<RawJob>();
        var path = "/repos/" + repo + "/issues?state=open&per_page=" + PER_PAGE;
        var page = retry.execute(() -> apiStrategy.extractPageWithPath(path));
        var pages = 1;

        jobs.addAll(page.jobs());
        while (page.nextUrl() != null) {
            if (page.jobs().isEmpty()) {
                break; // Link present but nothing consumed — stop (Lever parity)
            }
            if (pages >= MAX_PAGES_PER_REPO) {
                log.warn("{}: repo '{}' truncated after {} pages of Link pagination",
                        providerId, repo, MAX_PAGES_PER_REPO);
                break;
            }
            var nextUrl = page.nextUrl();
            page = retry.execute(() -> apiStrategy.extractPageWithPath(nextUrl));
            pages++;
            jobs.addAll(page.jobs());
        }
        return jobs;
    }

    /**
     * Maps one GitHub issue node to a {@link RawJob} per spec §4. Pull requests (nodes
     * carrying a {@code pull_request} key) and blank title/url nodes are skipped (null).
     */
    private RawJob mapNode(JsonNode node) {
        if (node.has("pull_request")) {
            return null;
        }
        var title = node.path("title").asText("");
        var url = node.path("html_url").asText("");
        if (title.isBlank() || url.isBlank()) {
            return null;
        }
        var rawDate = node.path("created_at").asText(null);
        if (rawDate != null && rawDate.length() >= 10) {
            rawDate = rawDate.substring(0, 10);
        }

        var labels = new ArrayList<String>();
        for (var label : node.path("labels")) {
            var name = label.path("name").asText("");
            if (!name.isBlank()) {
                labels.add(name);
            }
        }

        return new RawJob(
                title,
                parseCompany(title),
                url,
                node.path("body").asText(null),
                rawDate,
                location(labels, title),
                workModel(labels, title),
                "github",
                metadata(labels, node));
    }

    private static Map<String, String> metadata(List<String> labels, JsonNode node) {
        var metadata = new HashMap<String, String>();
        metadata.put("labels", String.join(",", labels));
        metadata.put("issue", String.valueOf(node.path("number").asInt()));
        return metadata;
    }

    /**
     * Best-effort company parse: the title suffix after the LAST {@code " - "} /
     * {@code " na "} / {@code " @ "} separator (e.g. {@code "... - Evertec"} → {@code Evertec}),
     * null when no separator is present or the suffix is blank (backend-br titles often
     * lack a company — the body may carry it and that is the normalizer's job).
     */
    private static String parseCompany(String title) {
        String bestSeparator = null;
        var bestIndex = -1;
        for (var separator : COMPANY_SEPARATORS) {
            var index = title.lastIndexOf(separator);
            if (index > bestIndex) {
                bestIndex = index;
                bestSeparator = separator;
            }
        }
        if (bestSeparator == null) {
            return null;
        }
        var company = title.substring(bestIndex + bestSeparator.length()).trim();
        return company.isEmpty() ? null : company;
    }

    /** Work signal from labels (exact match) first, then the {@code [prefix]} title hint. */
    private static String workModel(List<String> labels, String title) {
        for (var label : labels) {
            if (normalize(label).equals("remoto")) {
                return "Remoto";
            }
        }
        for (var label : labels) {
            if (normalize(label).equals("hibrido")) {
                return "Híbrido";
            }
        }
        return workModelFromPrefix(titlePrefix(title));
    }

    /** Location: location-ish labels joined; the {@code [prefix]} hint only when labels carry none. */
    private static String location(List<String> labels, String title) {
        var parts = new ArrayList<String>();
        for (var label : labels) {
            if (!isNonLocationLabel(label)) {
                parts.add(label);
            }
        }
        if (parts.isEmpty()) {
            var prefixLocation = locationFromPrefix(titlePrefix(title));
            if (prefixLocation != null) {
                parts.add(prefixLocation);
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static boolean isNonLocationLabel(String label) {
        return NON_LOCATION_LABELS.contains(normalize(label));
    }

    /** First bracketed prefix of a title, e.g. {@code "[Híbrido -SP] Dev"} → {@code "Híbrido -SP"}. */
    private static String titlePrefix(String title) {
        if (!title.startsWith("[")) {
            return null;
        }
        var end = title.indexOf(']');
        if (end < 0) {
            return null;
        }
        return title.substring(1, end);
    }

    private static String workModelFromPrefix(String prefix) {
        if (prefix == null) {
            return null;
        }
        var normalized = normalize(prefix);
        if (normalized.startsWith("remoto")) {
            return "Remoto";
        }
        if (normalized.startsWith("hibrido")) {
            return "Híbrido";
        }
        return null;
    }

    /** Location tokens from a prefix like {@code "[Híbrido -SP]"} → {@code "SP"} (model tokens excluded). */
    private static String locationFromPrefix(String prefix) {
        if (prefix == null) {
            return null;
        }
        var parts = new ArrayList<String>();
        for (var token : prefix.split("-")) {
            var trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var normalized = normalize(trimmed);
            if (normalized.equals("remoto") || normalized.equals("hibrido")) {
                continue; // workModel token, not a location
            }
            parts.add(trimmed);
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** Lowercase NFC-safe form (diacritics stripped) so {@code Híbrido} and {@code Hibrido} match equally. */
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    /** True when any cause in the chain is an HTTP 404 (spec §5: warn + skip). */
    private static boolean isNotFound(Exception e) {
        for (var current = (Throwable) e; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException rce && rce.getStatusCode().value() == 404) {
                return true;
            }
        }
        return false;
    }
}