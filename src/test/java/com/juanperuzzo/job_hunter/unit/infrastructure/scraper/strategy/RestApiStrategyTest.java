package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("RestApiStrategy tests")
class RestApiStrategyTest {

    private String baseUrl;
    private RestApiStrategy strategy;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
    }

    @Nested
    @DisplayName("Scenario 1: valid JSON response")
    class ValidResponse {

        @Test
        @DisplayName("extract should map JSON array to RawJob list")
        void extract_whenValidJson_shouldMapJobs() {
            stubFor(get(urlEqualTo("/"))
                    .willReturn(okJson("""
                        {"data": [
                          {"title": "Desenvolvedor Java", "company": "TechCo", "url": "https://a.com/1"},
                          {"title": "Desenvolvedor Python", "company": "DataCo", "url": "https://a.com/2"}
                        ]}
                        """)));

            strategy = new RestApiStrategy("test-api", baseUrl, 5, "data",
                    node -> new RawJob(
                            node.path("title").asText(),
                            node.path("company").asText(),
                            node.path("url").asText(),
                            null, null, null, null, new HashMap<>()));

            var jobs = strategy.extract();
            assertEquals(2, jobs.size());
            assertEquals("Desenvolvedor Java", jobs.get(0).title());
            assertEquals("TechCo", jobs.get(0).company());
        }

        @Test
        @DisplayName("extract should skip null RawJob entries")
        void extract_whenMapperReturnsNull_shouldSkip() {
            stubFor(get(urlEqualTo("/"))
                    .willReturn(okJson("""
                        {"data": [
                          {"title": "Valid", "url": "https://a.com/1"},
                          {"title": "", "url": "https://a.com/2"},
                          {"title": "Also Valid", "url": "https://a.com/3"}
                        ]}
                        """)));

            strategy = new RestApiStrategy("test-api", baseUrl, 5, "data",
                    node -> {
                        var title = node.path("title").asText();
                        if (title.isBlank()) return null;
                        return new RawJob(title, "", node.path("url").asText(),
                                null, null, null, null, null);
                    });

            var jobs = strategy.extract();
            assertEquals(2, jobs.size());
        }
    }

    @Nested
    @DisplayName("Scenario 2: edge cases")
    class EdgeCases {

        @Test
        @DisplayName("extract should return empty list for empty response body")
        void extract_whenEmptyResponse_shouldReturnEmpty() {
            stubFor(get(urlEqualTo("/"))
                    .willReturn(okJson("{\"data\": []}")));

            strategy = new RestApiStrategy("test-api", baseUrl, 5, "data",
                    node -> new RawJob("", "", "https://x.com", null, null, null, null, null));

            assertTrue(strategy.extract().isEmpty());
        }

        @Test
        @DisplayName("extract should throw ScraperException on HTTP error")
        void extract_whenHttpError_shouldThrow() {
            stubFor(get(urlEqualTo("/"))
                    .willReturn(serverError()));

            strategy = new RestApiStrategy("test-api", baseUrl, 5, "data",
                    node -> null);

            assertThrows(ScraperException.class, () -> strategy.extract());
        }

        @Test
        @DisplayName("extract should return empty for non-array jsonPath")
        void extract_whenJsonPathIsNotArray_shouldReturnEmpty() {
            stubFor(get(urlEqualTo("/"))
                    .willReturn(okJson("{\"data\": {\"not\": \"array\"}}")));

            strategy = new RestApiStrategy("test-api", baseUrl, 5, "data",
                    node -> null);

            assertTrue(strategy.extract().isEmpty());
        }

        @Test
        @DisplayName("extract should use custom path")
        void extractWithPath_shouldUseCustomPath() {
            stubFor(get(urlEqualTo("/jobs"))
                    .willReturn(okJson("""
                        [{"title": "Dev", "url": "https://a.com/1"}]
                        """)));

            strategy = new RestApiStrategy("test-api", baseUrl, 5, null,
                    node -> new RawJob(node.path("title").asText(), "", node.path("url").asText(),
                            null, null, null, null, null));

            var jobs = ((RestApiStrategy) strategy).extractWithPath("/jobs");
            assertEquals(1, jobs.size());
            assertEquals("Dev", jobs.get(0).title());
        }
    }
}
