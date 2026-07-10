package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("GupyProvider tests")
class GupyProviderTest {

    private String baseUrl;
    private GupyProvider provider;
    private ExponentialBackoffRetry retry;
    private RestApiStrategy apiStrategy;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        apiStrategy = new RestApiStrategy("gupy", baseUrl, 5, "data", GupyProviderTest::mapNode);
        provider = new GupyProvider("gupy", apiStrategy, retry, List.of("desenvolvedor"), 20);
    }

    private static RawJob mapNode(JsonNode node) {
        var title = node.path("name").asText("");
        var url = node.has("jobUrl") ? node.path("jobUrl").asText("") : node.path("careerPageUrl").asText("");
        if (title.isBlank() || url.isBlank()) return null;
        var rawDate = node.path("publishedDate").asText(null);
        if (rawDate != null && rawDate.length() >= 10) rawDate = rawDate.substring(0, 10);
        var location = node.path("city").asText(null);
        var state = node.path("state").asText(null);
        var locationStr = location != null ? (state != null ? location + ", " + state : location) : state;
        var isRemote = node.path("isRemoteWork").asBoolean(false);
        return new RawJob(title, node.path("careerPageName").asText(null), url,
                node.path("description").asText(null), rawDate, locationStr,
                isRemote ? "Remoto" : null, new HashMap<>());
    }

    @Nested
    @DisplayName("Scenario 1: valid JSON response")
    class ValidResponse {

        @Test
        @DisplayName("extract should return mapped RawJob list")
        void extract_whenValidResponse_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("desenvolvedor"))
                    .withQueryParam("limit", equalTo("20"))
                    .willReturn(okJson("""
                        {"data": [{
                          "id": 12345,
                          "name": "Desenvolvedor Java",
                          "careerPageName": "TechCo",
                          "jobUrl": "https://company.gupy.io/jobs/12345",
                          "publishedDate": "2026-07-01T14:00:00.000Z",
                          "description": "We need a developer",
                          "city": "São Paulo",
                          "state": "SP",
                          "isRemoteWork": false
                        }]}
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("Desenvolvedor Java", job.title());
            assertEquals("TechCo", job.company());
            assertEquals("https://company.gupy.io/jobs/12345", job.url());
            assertEquals("2026-07-01", job.rawDate());
            assertEquals("São Paulo, SP", job.location());
        }
    }

    @Nested
    @DisplayName("Scenario 2: deduplication")
    class Deduplication {

        @Test
        @DisplayName("extract should deduplicate by URL across keywords")
        void extract_whenSameUrlAcrossKeywords_shouldDeduplicate() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("desenvolvedor"))
                    .willReturn(okJson("""
                        {"data": [{"name": "Dev Java", "jobUrl": "https://a.com/1", "publishedDate": "2026-07-01"}]}
                        """)));
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("java"))
                    .willReturn(okJson("""
                        {"data": [{"name": "Dev Java", "jobUrl": "https://a.com/1", "publishedDate": "2026-07-01"}]}
                        """)));

            var dedupRetry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
            var dedupStrategy = new RestApiStrategy("gupy", baseUrl, 5, "data", GupyProviderTest::mapNode);
            var dedupProvider = new GupyProvider("gupy", dedupStrategy, dedupRetry, List.of("desenvolvedor", "java"), 20);
            var jobs = dedupProvider.extract();
            assertEquals(1, jobs.size());
        }
    }

    @Nested
    @DisplayName("Scenario 3: edge cases")
    class EdgeCases {

        @Test
        @DisplayName("extract should skip entries with blank URL")
        void extract_whenBlankUrl_shouldSkip() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [
                          {"name": "No URL", "jobUrl": "", "publishedDate": "2026-07-01"},
                          {"name": "Valid", "jobUrl": "https://a.com/1", "publishedDate": "2026-07-01"}
                        ]}
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());
        }

        @Test
        @DisplayName("extract should return empty when no keywords match")
        void extract_whenKeywordHasNoResults_shouldReturnEmpty() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("{\"data\": []}")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }

        @Test
        @DisplayName("extract should handle remote work jobs")
        void extract_whenRemoteJob_shouldSetWorkModel() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [{
                          "name": "Dev Remoto",
                          "jobUrl": "https://a.com/remote",
                          "isRemoteWork": true,
                          "publishedDate": "2026-07-01"
                        }]}
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());
            assertEquals("Remoto", jobs.get(0).workModel());
        }
    }
}
