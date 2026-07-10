package com.juanperuzzo.job_hunter.infrastructure.scraper.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
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
        this.providerId = providerId;
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
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
        try {
            var response = restClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ScraperException(providerId + " endpoint returned status " + res.getStatusCode());
                    })
                    .body(String.class);

            if (response == null || response.isBlank()) {
                log.warn("{} returned empty response", providerId);
                return List.of();
            }

            var root = objectMapper.readTree(response);
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

        } catch (Exception e) {
            throw new ScraperException(providerId + " extraction failed: " + e.getMessage(), e);
        }
    }
}
