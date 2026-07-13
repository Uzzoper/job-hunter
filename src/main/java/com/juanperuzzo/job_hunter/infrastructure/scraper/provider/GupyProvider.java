package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class GupyProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GupyProvider.class);
    private static final String JSON_PATH = "data";

    private final String providerId;
    private final RestApiStrategy apiStrategy;
    private final ExponentialBackoffRetry retry;
    private final List<String> keywords;
    private final int limit;

    public GupyProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int limit,
            ExponentialBackoffRetry retry) {
        this.providerId = "gupy";
        this.keywords = keywords;
        this.limit = limit;
        this.retry = retry;
        this.apiStrategy = new RestApiStrategy(providerId, baseUrl, timeoutSeconds, JSON_PATH, this::mapNode);
    }

    public GupyProvider(
            String providerId,
            RestApiStrategy apiStrategy,
            ExponentialBackoffRetry retry,
            List<String> keywords,
            int limit) {
        this.providerId = providerId;
        this.apiStrategy = apiStrategy;
        this.retry = retry;
        this.keywords = keywords;
        this.limit = limit;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();

        for (var keyword : keywords) {
            try {
                var path = "/api/v1/jobs?jobName=" + urlEncode(keyword) + "&limit=" + limit;
                var jobs = retry.execute(() -> apiStrategy.extractWithPath(path));

                for (var job : jobs) {
                    uniqueJobs.putIfAbsent(job.url(), job);
                }

                log.debug("{}: fetched {} jobs for keyword '{}'", providerId, jobs.size(), keyword);
            } catch (Exception e) {
                log.error("{}: failed to fetch keyword '{}': {}", providerId, keyword, e.getMessage());
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        log.info("{}: total unique jobs fetched: {}", providerId, result.size());
        return result;
    }

    private RawJob mapNode(JsonNode node) {
        var title = node.path("name").asText("");
        var url = getJobUrl(node);
        if (title.isBlank() || url.isBlank()) {
            return null;
        }
        var rawDate = node.path("publishedDate").asText(null);
        if (rawDate != null && rawDate.length() >= 10) {
            rawDate = rawDate.substring(0, 10);
        }
        var location = node.path("city").asText(null);
        var state = node.path("state").asText(null);
        var locationStr = location != null
                ? (state != null ? location + ", " + state : location)
                : state;
        var isRemote = node.path("isRemoteWork").asBoolean(false);
        var workModel = isRemote ? "Remoto" : null;
        var company = node.path("careerPageName").asText(null);

        return new RawJob(
                title,
                company,
                url,
                node.path("description").asText(null),
                rawDate,
                locationStr,
                workModel,
                "gupy",
                null);
    }

    private static String getJobUrl(JsonNode node) {
        if (node.has("jobUrl")) {
            var jobUrl = node.path("jobUrl").asText("");
            if (!jobUrl.isBlank()) return jobUrl;
        }
        return node.path("careerPageUrl").asText("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
