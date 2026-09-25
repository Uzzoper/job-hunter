package com.juanperuzzo.job_hunter.infrastructure.scraper.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class RestApiStrategy implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(RestApiStrategy.class);

    private final String providerId;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String jsonPath;
    private final Function<JsonNode, RawJob> mapper;

    public RestApiStrategy(
            String providerId,
            String baseUrl,
            int timeoutSeconds,
            String jsonPath,
            Function<JsonNode, RawJob> mapper) {
        this(providerId, baseUrl, timeoutSeconds, jsonPath, mapper, Map.of());
    }

    public RestApiStrategy(
            String providerId,
            String baseUrl,
            int timeoutSeconds,
            String jsonPath,
            Function<JsonNode, RawJob> mapper,
            Map<String, String> defaultHeaders) {
        this.providerId = providerId;
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
        var builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory);
        if (defaultHeaders != null && !defaultHeaders.isEmpty()) {
            builder.defaultHeaders(headers -> defaultHeaders.forEach(headers::set));
        }
        this.restClient = builder.build();
        this.objectMapper = new ObjectMapper();
        this.jsonPath = jsonPath;
        this.mapper = mapper;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        return extractWithPath(null);
    }

    public List<RawJob> extractWithPath(String path) {
        return extractPageWithPath(path).jobs();
    }

    /**
     * One raw JSON page plus the RFC 5988 {@code Link: rel="next"} URL (null on the
     * last page) — callers drive pagination themselves (e.g. GithubVagasProvider).
     */
    public record Page(List<RawJob> jobs, String nextUrl) {}

    /**
     * Performs the GET (wrapping HTTP errors in {@link ScraperException}), maps the
     * body to {@link RawJob}s and parses the next-page URL from the Link header.
     * Absolute URLs (Link targets) override the configured baseUrl; relative paths
     * are resolved against it.
     */
    public Page extractPageWithPath(String path) {
        try {
            // No custom onStatus handler: RestClient's default error handling throws a
            // RestClientResponseException (e.g. HttpClientErrorException) carrying the HTTP
            // status. The catch below wraps it preserving the cause so callers can inspect
            // getStatusCode() (e.g. GupyProvider's 401/403 auth fast-path) instead of
            // relying on message substrings.
            RestClient.RequestHeadersSpec<?> request;
            if (path != null && (path.startsWith("http://") || path.startsWith("https://"))) {
                request = restClient.get().uri(URI.create(path));
            } else {
                request = restClient.get().uri(path);
            }
            var response = request.retrieve().toEntity(String.class);

            var nextUrl = parseNextLink(response.getHeaders().getFirst(HttpHeaders.LINK));
            return new Page(mapBody(response.getBody()), nextUrl);

        } catch (Exception e) {
            throw new ScraperException(providerId + " extraction failed: " + e.getMessage(), e);
        }
    }

    /** Parses {@code <...>; rel="next"} segments out of an RFC 5988 Link header (null when absent). */
    private static String parseNextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        for (var part : linkHeader.split(",")) {
            if (!part.contains("rel=\"next\"")) {
                continue;
            }
            var start = part.indexOf('<');
            var end = part.indexOf('>');
            if (start >= 0 && end > start) {
                return part.substring(start + 1, end);
            }
        }
        return null;
    }

    private List<RawJob> mapBody(String body) throws IOException {
        if (body == null || body.isBlank()) {
            log.warn("{} returned empty response", providerId);
            return List.of();
        }

        var root = objectMapper.readTree(body);
        JsonNode data = root;
        if (jsonPath != null && !jsonPath.isBlank()) {
            data = root.at("/" + jsonPath.replace(".", "/"));
        }

        if (data == null || !data.isArray()) {
            log.warn("{} expected array at '{}' but got: {}", providerId, jsonPath,
                    data != null ? data.getNodeType() : "null");
            return List.of();
        }

        var results = new ArrayList<RawJob>();
        for (var node : data) {
            try {
                var rawJob = mapper.apply(node);
                if (rawJob != null) {
                    results.add(rawJob);
                }
            } catch (Exception e) {
                log.warn("{} failed to map node: {}", providerId, e.getMessage());
            }
        }
        return results;
    }
}
