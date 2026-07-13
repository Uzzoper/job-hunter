package com.juanperuzzo.job_hunter.infrastructure.scraper.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.config.LinkedInScraperProperties;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LinkedInScraperClient implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(LinkedInScraperClient.class);

    private static final String PROVIDER_ID = "linkedin";
    private static final String SEARCH_PATH = "/api/jobs";
    private static final String DETAIL_PATH_PREFIX = "/api/jobs/";
    private static final String BASE_JOB_URL = "https://www.linkedin.com/jobs/view/";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LinkedInScraperProperties properties;

    public LinkedInScraperClient(LinkedInScraperProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();

        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.timeoutSeconds() * 1000);

        this.restClient = RestClient.builder()
                .baseUrl(properties.serviceUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<RawJob> extract() {
        var keywords = properties.keywords().isEmpty() ? "desenvolvedor" : properties.keywords().get(0);
        var location = properties.locations().isBlank() ? "Brazil" : properties.locations().split(",")[0].trim();

        var encodedKeywords = URLEncoder.encode(keywords, StandardCharsets.UTF_8);
        var encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8);

        var uri = URI.create(SEARCH_PATH + "?keywords=" + encodedKeywords + "&location=" + encodedLocation);

        try {
            var response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            handleErrorResponse(res.getStatusCode().value(), res.getBody().readAllBytes());
                        } catch (Exception e) {
                            handleErrorResponse(res.getStatusCode().value(), new byte[0]);
                        }
                    })
                    .body(String.class);

            if (response == null || response.isBlank()) {
                log.warn("{} returned empty response", PROVIDER_ID);
                return List.of();
            }

            var root = objectMapper.readTree(response);
            if (!root.path("success").asBoolean(false)) {
                var error = root.path("error");
                var code = error.path("code").asText("UNKNOWN");
                var message = error.path("message").asText("Unknown error");
                throw new ScraperException(PROVIDER_ID + " API error: " + code + " - " + message);
            }

            var data = root.path("data");
            if (data == null || !data.isArray()) {
                log.warn("{} expected array at 'data' but got: {}", PROVIDER_ID, data != null ? data.getNodeType() : "null");
                return List.of();
            }

            var results = new ArrayList<RawJob>();
            for (var node : data) {
                try {
                    var rawJob = mapToRawJob(node);
                    if (rawJob != null) {
                        results.add(rawJob);
                    }
                } catch (Exception e) {
                    log.warn("{} failed to map node: {}", PROVIDER_ID, e.getMessage());
                }
            }

            // Limit to maxJobs BEFORE enrichment to avoid wasteful detail calls
            if (results.size() > properties.maxJobs()) {
                results = new ArrayList<>(results.subList(0, properties.maxJobs()));
            }

            var enriched = new ArrayList<RawJob>();
            for (int i = 0; i < results.size(); i++) {
                var card = results.get(i);
                var jobId = card.metadata().get("jobId");
                if (jobId != null && !jobId.isBlank()) {
                    try {
                        if (i > 0) {
                            Thread.sleep(properties.detailFetchDelayMillis());
                        }
                        var detail = extractDetail(jobId);
                        card = mergeDetailIntoRawJob(card, detail);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("{} enrichment interrupted for job {}", PROVIDER_ID, jobId);
                    } catch (Exception e) {
                        log.warn("{} failed to enrich job {}: {}", PROVIDER_ID, jobId, e.getMessage());
                    }
                }
                enriched.add(card);
            }

            log.info("{}: fetched {} jobs", PROVIDER_ID, enriched.size());
            return enriched;

        } catch (ScraperException e) {
            throw e;
        } catch (Exception e) {
            throw new ScraperException(PROVIDER_ID + " extraction failed: " + e.getMessage(), e);
        }
    }

    public RawJob extractDetail(String jobId) {
        var uri = URI.create(DETAIL_PATH_PREFIX + jobId);

        try {
            var response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            handleErrorResponse(res.getStatusCode().value(), res.getBody().readAllBytes());
                        } catch (Exception e) {
                            handleErrorResponse(res.getStatusCode().value(), new byte[0]);
                        }
                    })
                    .body(String.class);

            if (response == null || response.isBlank()) {
                throw new ScraperException(PROVIDER_ID + " detail endpoint returned empty response");
            }

            var root = objectMapper.readTree(response);
            if (!root.path("success").asBoolean(false)) {
                var error = root.path("error");
                var code = error.path("code").asText("UNKNOWN");
                var message = error.path("message").asText("Unknown error");
                throw new ScraperException(PROVIDER_ID + " detail API error: " + code + " - " + message);
            }

            var data = root.path("data");
            if (data == null || data.isMissingNode()) {
                throw new ScraperException(PROVIDER_ID + " detail response missing data");
            }

            return mapToRawJob(data);

        } catch (ScraperException e) {
            throw e;
        } catch (Exception e) {
            throw new ScraperException(PROVIDER_ID + " detail extraction failed: " + e.getMessage(), e);
        }
    }

    private RawJob mapToRawJob(JsonNode node) {
        var id = node.path("id").asText("");
        var title = node.path("title").asText("");
        var company = node.path("company").asText("");
        var location = node.path("location").asText("");
        var postedAt = node.path("postedAt").asText("");
        var description = node.path("description").asText("");

        if (id.isBlank() || title.isBlank()) {
            return null;
        }

        var url = BASE_JOB_URL + id;

        var metadata = new HashMap<String, String>();
        metadata.put("jobId", id);

        var requirements = node.path("requirements");
        if (requirements.isArray()) {
            var reqList = new ArrayList<String>();
            for (var req : requirements) {
                reqList.add(req.asText());
            }
            if (!reqList.isEmpty()) {
                metadata.put("requirements", String.join("; ", reqList));
            }
        }

        var jobType = node.path("jobType").asText("");
        if (!jobType.isBlank()) {
            metadata.put("jobType", jobType);
        }

        var seniority = node.path("seniority").asText("");
        if (!seniority.isBlank()) {
            metadata.put("seniority", seniority);
        }

        var salary = node.path("salary").asText("");
        if (!salary.isBlank()) {
            metadata.put("salary", salary);
        }

        return new RawJob(
                title,
                company,
                url,
                description,
                postedAt,
                location,
                "",
                PROVIDER_ID,
                metadata
        );
    }

    private RawJob mergeDetailIntoRawJob(RawJob card, RawJob detail) {
        var description = detail.description();
        if (description == null || description.isBlank()) {
            description = card.description();
        }

        var metadata = new HashMap<>(card.metadata());
        if (detail.metadata() != null) {
            metadata.putAll(detail.metadata());
        }

        return new RawJob(
                card.title(),
                card.company(),
                card.url(),
                description,
                card.rawDate(),
                card.location(),
                card.workModel(),
                card.source(),
                metadata
        );
    }

    private void handleErrorResponse(int statusCode, byte[] body) {
        String bodyStr = body != null ? new String(body, StandardCharsets.UTF_8) : "";

        switch (statusCode) {
            case 429 -> throw new ScraperException(PROVIDER_ID + " rate limited (429): " + bodyStr);
            case 503 -> throw new ScraperException(PROVIDER_ID + " service unavailable (503): " + bodyStr);
            case 404 -> throw new ScraperException(PROVIDER_ID + " not found (404): " + bodyStr);
            default -> throw new ScraperException(PROVIDER_ID + " returned HTTP " + statusCode + ": " + bodyStr);
        }
    }
}