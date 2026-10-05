package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.RetryStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gupy provider speaking the official candidate MCP server over Streamable HTTP
 * (spec: {@code docs/specs/gupy-mcp-provider.md}).
 *
 * <p>The public Gupy search JSON API is dead on every probed host, so this provider
 * replaces {@link GupyProvider}'s REST wiring; emits source/providerId "gupy" for catalog continuity so both may coexist during parity.
 * provider id — downstream normalisation, dedupe and the #74 company-domain
 * machinery are untouched.
 *
 * <p>Wire shape recorded live on 2026-10-04 against
 * {@code https://candidates.mcp.api.gupy.io/mcp} (no auth): every call is a JSON-RPC
 * {@code tools/call} POST answered with an SSE frame
 * ({@code event: message} + {@code data: {...}}) whose {@code result.content[0].text}
 * is a JSON <em>string</em> holding {@code {"data":{"data":[...],"pagination":{...}}}}.
 * Plain {@code application/json} answers are accepted too.
 *
 * <p>Pagination note: the server caps {@code pagination.total} at {@code limit}, so
 * with {@code limit=100} an {@code offset > 0} request replays page 1. The offset
 * loop therefore stops when a page adds no new URL, instead of spinning forever.
 */
public class GupyMcpProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GupyMcpProvider.class);

    private static final String PROVIDER_ID = "gupy";
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String TOOL_SEARCH = "search_jobs";
    private static final String TOOL_JOB_BY_ID = "get_job_by_id";
    private static final String JSON_RPC = "2.0";
    private static final String CLIENT_NAME = "job-hunter";
    private static final String CLIENT_VERSION = "1.0";

    /** Work-model vocabulary shared with the other providers (see {@code WorkModelSignals}). */
    private static final String WORK_MODEL_REMOTE = "Remoto";
    private static final String WORK_MODEL_HYBRID = "Híbrido";
    private static final String WORK_MODEL_ONSITE = "Presencial";

    /**
     * Aggregator suffixes stripped from {@code careerPageName} ({@code "X - Linkedin"} → {@code "X"}).
     * Only known job-board names are stripped: a Gupy career page legitimately carries
     * dashes in its own name ("TMSA - TECNOLOGIA EM MOVIMENTAÇÃO S/A"), so cutting at
     * the first {@code " - "} would truncate real companies.
     */
    private static final Set<String> PORTAL_SUFFIXES = Set.of(
            "linkedin", "gupy", "glassdoor", "indeed", "infojobs", "vagas.com", "vaga-ja.com");

    /** Countries treated as Brazil — their name is never appended to the location. */
    private static final Set<String> BRAZIL = Set.of("brasil", "brazil", "br");

    private final String mcpUrl;
    private final org.springframework.web.client.RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<String> keywords;
    private final int limit;
    private final int maxJobs;
    private final RetryStrategy retry;
    private final CompanyDomainResolverPort companyDomainResolver;
    private final int maxDetailDomains;
    private final AtomicInteger requestIds = new AtomicInteger();

    public GupyMcpProvider(
            String mcpUrl,
            int timeoutSeconds,
            List<String> keywords,
            int limit,
            int maxJobs,
            RetryStrategy retry,
            CompanyDomainResolverPort companyDomainResolver,
            int maxDetailDomains) {
        this.mcpUrl = mcpUrl;
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
        this.restClient = org.springframework.web.client.RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = new ObjectMapper();
        this.keywords = keywords != null ? keywords : List.of();
        this.limit = limit;
        this.maxJobs = maxJobs;
        this.retry = retry;
        this.companyDomainResolver = companyDomainResolver;
        this.maxDetailDomains = maxDetailDomains;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Runs the keyword loop against the MCP server, with offset pagination,
     * deduplication by URL, optional description fallback via {@code get_job_by_id}
     * and optional company-domain resolution (#74).
     */
    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new LinkedHashMap<String, RawJob>();

        try {
            initialize();
        } catch (Exception e) {
            // The handshake is a protocol nicety, not a gate: log and keep going so a
            // stateless server that answers tools/call anyway still yields jobs.
            log.warn("{}: MCP initialize failed ({}), continuing with tools/call", PROVIDER_ID, e.getMessage());
        }

        for (var keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                log.debug("{}: skipping blank keyword (null or whitespace)", PROVIDER_ID);
                continue;
            }
            if (uniqueJobs.size() >= maxJobs) {
                log.debug("{}: max-jobs ({}) reached, skipping remaining keywords", PROVIDER_ID, maxJobs);
                break;
            }
            try {
                collectQuery(keyword, uniqueJobs);
            } catch (Exception e) {
                // Per-query isolation (spec §5): a dead query is logged and skipped, the
                // fetch as a whole never fails.
                log.error("{}: failed for keyword '{}': {}", PROVIDER_ID, keyword, e.getMessage());
            }
        }

        var result = resolveCompanyDomains(List.copyOf(uniqueJobs.values()));
        if (result.size() > maxJobs) {
            result = result.subList(0, maxJobs);
        }
        log.info("{}: total unique jobs fetched: {}", PROVIDER_ID, result.size());
        return result;
    }

    /** One {@code initialize} handshake per fetch (spec §3) — no auth, protocol negotiation only. */
    private void initialize() {
        var params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        var clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);

        var result = postRpc("initialize", params).path("result");
        var negotiated = result.path("protocolVersion").asText(null);
        log.debug("{}: MCP initialized (protocolVersion={}, server={})", PROVIDER_ID, negotiated,
                result.path("serverInfo").path("name").asText("?"));
    }

    /**
     * Runs the offset loop for a single keyword, appending new URLs to {@code sink}.
     * Stops on a short page, on the merged {@code maxJobs} cap, or when a page adds no
     * new URL (the live server replays page 1 once {@code offset} exceeds its capped total).
     */
    private void collectQuery(String keyword, Map<String, RawJob> sink) {
        int offset = 0;
        while (sink.size() < maxJobs) {
            var pageOffset = offset;
            var page = retry.execute(() -> fetchPage(keyword, pageOffset));
            if (page.isEmpty()) {
                log.debug("{}: empty page for keyword '{}' at offset {} — done", PROVIDER_ID, keyword, offset);
                return;
            }

            int added = 0;
            for (var item : page) {
                var job = mapNode(withFallbackDescription(item));
                if (job == null) {
                    continue;
                }
                if (sink.putIfAbsent(job.url(), job) == null) {
                    added++;
                }
            }
            log.debug("{}: page offset {} for keyword '{}' → {} items, {} new", PROVIDER_ID, offset,
                    keyword, page.size(), added);

            if (page.size() < limit) {
                return;
            }
            if (added == 0 && offset > 0) {
                log.debug("{}: offset {} replayed already-seen URLs for keyword '{}', stopping pagination",
                        PROVIDER_ID, offset, keyword);
                return;
            }
            offset += limit;
        }
    }

    /** One {@code search_jobs} page as raw items (mapping happens in the caller). */
    private List<JsonNode> fetchPage(String keyword, int offset) {
        var arguments = objectMapper.createObjectNode();
        arguments.put("term", keyword);
        arguments.put("limit", limit);
        arguments.put("offset", offset);

        var payload = callTool(TOOL_SEARCH, arguments);
        var jobs = payload.path("data").path("data");
        if (!jobs.isArray()) {
            log.warn("{}: search_jobs returned no job array at offset {}: {}", PROVIDER_ID, offset, payload);
            return List.of();
        }
        var items = new ArrayList<JsonNode>(jobs.size());
        for (var item : jobs) {
            items.add(item);
        }
        return items;
    }

    /**
     * {@code get_job_by_id} fallback (spec §3): the search result already inlines the
     * description, so the detail call is made only when the search item has none.
     * A failed fallback is logged and the search item is kept as-is.
     */
    private JsonNode withFallbackDescription(JsonNode item) {
        var description = item.path("description").asText("");
        if (!description.isBlank() || !item.hasNonNull("id")) {
            return item;
        }
        var id = item.get("id");
        var arguments = objectMapper.createObjectNode();
        arguments.set("id", id);
        try {
            var detail = callTool(TOOL_JOB_BY_ID, arguments).path("data");
            var detailDescription = detail.path("description").asText("");
            if (detail.isObject() && !detailDescription.isBlank()) {
                log.debug("{}: filled description for job {} via {}", PROVIDER_ID, id.asText(), TOOL_JOB_BY_ID);
                return detail;
            }
        } catch (Exception e) {
            log.warn("{}: {} failed for job {}: {}", PROVIDER_ID, TOOL_JOB_BY_ID, id.asText(), e.getMessage());
        }
        return item;
    }

    /** Calls an MCP tool and returns the parsed {@code content[0].text} payload. */
    private JsonNode callTool(String tool, JsonNode arguments) {
        var params = objectMapper.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);

        var result = postRpc("tools/call", params).path("result");
        if (result.path("isError").asBoolean(false)) {
            throw new ScraperException(PROVIDER_ID + " MCP tool error from " + tool + ": " + firstText(result));
        }
        var text = firstText(result);
        if (text == null || text.isBlank()) {
            throw new ScraperException(PROVIDER_ID + " MCP tool " + tool + " returned no content text");
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new ScraperException(PROVIDER_ID + " MCP tool " + tool + " returned unparseable text: "
                    + e.getMessage(), e);
        }
    }

    /** First {@code content[].text} entry of a tool result (null when absent). */
    private static String firstText(JsonNode result) {
        var content = result.path("content");
        if (!content.isArray()) {
            return null;
        }
        for (var entry : content) {
            var text = entry.path("text").asText(null);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /** POSTs one JSON-RPC request and returns the parsed envelope. */
    private JsonNode postRpc(String method, JsonNode params) {
        var request = objectMapper.createObjectNode();
        request.put("jsonrpc", JSON_RPC);
        request.put("id", requestIds.incrementAndGet());
        request.put("method", method);
        request.set("params", params);

        String body;
        try {
            // Read bytes, not String: an SSE response carries no charset in its
            // Content-Type, so the String converter would fall back to ISO-8859-1 and
            // mangle every accented field ("ISA SAÚDE" → "ISA SAÃšDE").
            var payload = restClient.post()
                    .uri(mcpUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Accept", ACCEPT)
                    .body(request.toString())
                    .retrieve()
                    .body(byte[].class);
            body = payload != null ? new String(payload, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            throw new ScraperException(PROVIDER_ID + " MCP " + method + " call failed: " + e.getMessage(), e);
        }
        if (body == null || body.isBlank()) {
            throw new ScraperException(PROVIDER_ID + " MCP " + method + " returned an empty body");
        }

        var payload = sseData(body);
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new ScraperException(PROVIDER_ID + " MCP " + method + " returned an unparseable body: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Unwraps an MCP Streamable HTTP SSE frame ({@code event: message} + {@code data: {...}})
     * to its JSON payload; a plain JSON body is returned unchanged. The last {@code data:}
     * line wins, which is what the MCP stream carries for a single response.
     */
    private static String sseData(String body) {
        var trimmed = body.stripLeading();
        if (!trimmed.startsWith("event:") && !trimmed.startsWith("data:")) {
            return trimmed;
        }
        String payload = null;
        for (var line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                payload = line.substring("data:".length()).strip();
            }
        }
        return payload != null ? payload : trimmed;
    }

    // ------------------------------------------------------------------ mapping

    /**
     * Maps a search item to {@link RawJob} per spec §4. Returns {@code null} for a
     * skipped entry (blank title or blank {@code jobUrl} — {@code jobUrl} has no
     * fallback, the board URL is what feeds the #74 domain machinery).
     *
     * <ul>
     *   <li>title ← {@code name}; company ← {@code careerPageName} with a known
     *       aggregator suffix stripped; url ← {@code jobUrl}</li>
     *   <li>description ← the full inline {@code description}</li>
     *   <li>rawDate ← {@code publishedDate} truncated to {@code yyyy-MM-dd}</li>
     *   <li>location ← {@code city} + {@code state} (+ {@code country} when not Brazil)</li>
     *   <li>workModel ← {@code workplaceType} mapped to the shared vocabulary</li>
     *   <li>metadata ← {@code companyId}, {@code disabilities}, {@code salary}
     *       (its {@code label}) and {@code type}</li>
     * </ul>
     */
    private RawJob mapNode(JsonNode node) {
        var title = node.path("name").asText("");
        var url = node.path("jobUrl").asText("");
        if (title.isBlank() || url.isBlank()) {
            log.debug("{}: skipping entry with blank title/url (id={})", PROVIDER_ID, node.path("id").asText("?"));
            return null;
        }

        var description = node.path("description").asText("");
        return new RawJob(
                title,
                stripPortalSuffix(node.path("careerPageName").asText(null)),
                url,
                description.isBlank() ? null : description,
                rawDate(node.path("publishedDate").asText(null)),
                composeLocation(node),
                workModel(node.path("workplaceType").asText(null)),
                PROVIDER_ID,
                metadata(node));
    }

    /** {@code publishedDate} ISO → {@code yyyy-MM-dd} (unchanged when already day-precision). */
    private static String rawDate(String publishedDate) {
        if (publishedDate == null || publishedDate.isBlank()) {
            return null;
        }
        var value = publishedDate.trim();
        return value.length() >= 10 ? value.substring(0, 10) : value;
    }

    /** {@code city} + {@code state}, with {@code country} appended only outside Brazil. */
    private static String composeLocation(JsonNode node) {
        var parts = new ArrayList<String>(3);
        var city = node.path("city").asText("");
        var state = node.path("state").asText("");
        var country = node.path("country").asText("");
        if (!city.isBlank()) {
            parts.add(city.trim());
        }
        if (!state.isBlank()) {
            parts.add(state.trim());
        }
        if (!country.isBlank() && !BRAZIL.contains(country.trim().toLowerCase(Locale.ROOT))) {
            parts.add(country.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * {@code workplaceType} → shared vocabulary. The live values are {@code remote},
     * {@code hybrid} and {@code on-site}; anything unknown (including an absent field)
     * yields {@code null} rather than an invented label.
     */
    private static String workModel(String workplaceType) {
        if (workplaceType == null || workplaceType.isBlank()) {
            return null;
        }
        var normalized = workplaceType.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (normalized) {
            case "remote", "remoto" -> WORK_MODEL_REMOTE;
            case "hybrid", "hibrido" -> WORK_MODEL_HYBRID;
            case "onsite", "presencial" -> WORK_MODEL_ONSITE;
            default -> null;
        };
    }

    /** Present-only metadata: {@code companyId}, {@code disabilities}, {@code salary}, {@code type}. */
    private Map<String, String> metadata(JsonNode node) {
        var metadata = new LinkedHashMap<String, String>();
        if (node.hasNonNull("companyId")) {
            metadata.put("companyId", node.get("companyId").asText());
        }
        if (node.hasNonNull("disabilities")) {
            metadata.put("disabilities", node.get("disabilities").asText());
        }
        var salary = node.get("salary");
        if (salary != null && !salary.isNull()) {
            // Live shape is an object ({status,label,confidence}); only the human label is a string.
            var label = salary.isObject() ? salary.path("label").asText("") : salary.asText("");
            if (!label.isBlank()) {
                metadata.put("salary", label.trim());
            }
        }
        if (node.hasNonNull("type")) {
            metadata.put("type", node.get("type").asText());
        }
        return metadata;
    }

    /**
     * Strips a trailing aggregator suffix from the career page name
     * ({@code "X - Linkedin"} → {@code "X"}), keeping real dashes: only the suffixes in
     * {@link #PORTAL_SUFFIXES} are cut, so "TMSA - TECNOLOGIA EM MOVIMENTAÇÃO S/A"
     * survives intact.
     */
    private static String stripPortalSuffix(String careerPageName) {
        if (careerPageName == null || careerPageName.isBlank()) {
            return careerPageName;
        }
        var separator = careerPageName.indexOf(" - ");
        if (separator < 0) {
            return careerPageName;
        }
        var suffix = careerPageName.substring(separator + 3).trim().toLowerCase(Locale.ROOT);
        return PORTAL_SUFFIXES.contains(suffix)
                ? careerPageName.substring(0, separator).trim()
                : careerPageName;
    }

    // ------------------------------------------------- #74 company-domain resolution

    /**
     * Resolve a company website per career-page host and attach it as
     * {@code companyWebsite} metadata to every job of that host (#74 spec), reused
     * unchanged from the retired REST provider.
     *
     * <p>Per-host failures are non-fatal: the host is skipped with a warning and its
     * jobs keep their list-level metadata. When resolution is disabled (no resolver or
     * a non-positive cap) the jobs are returned unchanged.
     */
    private List<RawJob> resolveCompanyDomains(List<RawJob> jobs) {
        if (jobs.isEmpty() || companyDomainResolver == null || maxDetailDomains <= 0) {
            return jobs;
        }

        var jobUrls = jobs.stream().map(RawJob::url).toList();
        var resolvedWebsites = companyDomainResolver.resolveCompanyWebsites(jobUrls, maxDetailDomains);
        if (resolvedWebsites.isEmpty()) {
            return jobs;
        }

        var results = new ArrayList<RawJob>(jobs.size());
        for (var job : jobs) {
            var website = resolvedWebsites.get(job.url());
            results.add(website == null ? job : withCompanyWebsite(job, website));
        }
        return results;
    }

    private static RawJob withCompanyWebsite(RawJob job, String website) {
        var metadata = new LinkedHashMap<>(job.metadata());
        metadata.put("companyWebsite", website);
        return new RawJob(
                job.title(), job.company(), job.url(), job.description(),
                job.rawDate(), job.location(), job.workModel(), job.source(), metadata);
    }
}