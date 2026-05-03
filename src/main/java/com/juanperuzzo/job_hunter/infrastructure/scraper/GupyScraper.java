package com.juanperuzzo.job_hunter.infrastructure.scraper;

import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class GupyScraper implements ScraperPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<String> keywords;
    private final List<String> excludeKeywords;
    private final List<String> locations;
    private final int timeoutSeconds;
    private final int limit;

    public GupyScraper(String baseUrl, List<String> keywords, List<String> excludeKeywords, List<String> locations, int limit, int timeoutSeconds) {
        this.keywords = keywords;
        this.excludeKeywords = excludeKeywords;
        this.locations = locations;
        this.limit = limit;
        this.timeoutSeconds = timeoutSeconds;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Job> fetch() {
        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/jobs")
                            .queryParam("jobName", "desenvolvedor")
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ScraperException("Gupy endpoint returned status " + res.getStatusCode());
                    })
                    .body(String.class);

            return parseResponse(response);
        } catch (ScraperException e) {
            throw e;
        } catch (Exception e) {
            if (e.getMessage() != null && (e.getMessage().toLowerCase().contains("timeout") ||
                    e.getMessage().toLowerCase().contains("timed out"))) {
                throw new ScraperException("Gupy scraper timed out after " + timeoutSeconds + "s", e);
            }
            throw new ScraperException("Failed to fetch jobs: " + e.getMessage(), e);
        }
    }

    private List<Job> parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");

            List<Job> jobs = new ArrayList<>();
            if (data == null || !data.isArray()) {
                return jobs;
            }

            for (JsonNode node : data) {
                if (!matchesKeywords(node.path("name").asText(""))) {
                    continue;
                }
                if (isExcluded(node.path("name").asText(""))) {
                    continue;
                }
                if (!matchesLocation(node)) {
                    continue;
                }

                Job job = mapToJob(node);
                if (job != null) {
                    jobs.add(job);
                }
            }

            return jobs;
        } catch (Exception e) {
            throw new ScraperException("Failed to parse Gupy response: " + e.getMessage(), e);
        }
    }

    private Job mapToJob(JsonNode node) {
        String title = node.path("name").asText("");
        String company = node.path("careerPageName").asText("");
        String url = node.has("jobUrl") ? node.path("jobUrl").asText("") : node.path("careerPageUrl").asText("");
        String publishedDate = node.path("publishedDate").asText("");

        if (title.isEmpty() || url.isEmpty()) {
            return null;
        }

        String description = !node.path("description").isNull()
                ? node.path("description").asText("")
                : "";

        LocalDate postedAt = null;
        if (!publishedDate.isEmpty() && publishedDate.length() >= 10) {
            postedAt = LocalDate.parse(publishedDate.substring(0, 10));
        }

        return new Job(null, title, company, url, description, postedAt, Optional.empty());
    }

    private boolean matchesKeywords(String title) {
        if (title.isEmpty()) {
            return false;
        }
        return keywords.stream()
                .anyMatch(k -> title.toLowerCase().contains(k.toLowerCase()));
    }

    private boolean isExcluded(String title) {
        if (title.isEmpty() || excludeKeywords.isEmpty()) {
            return false;
        }
        return excludeKeywords.stream()
                .anyMatch(k -> title.toLowerCase().contains(k.toLowerCase()));
    }

    private boolean matchesLocation(JsonNode node) {
        if (locations.isEmpty()) {
            return true;
        }
        boolean isRemote = node.path("isRemoteWork").asBoolean(false);
        if (isRemote) {
            return true;
        }
        String city = node.path("city").asText("").toLowerCase();
        String state = node.path("state").asText("").toLowerCase();
        return locations.stream()
                .anyMatch(loc -> city.contains(loc.toLowerCase())
                              || state.contains(loc.toLowerCase()));
    }
}
