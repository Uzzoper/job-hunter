package com.juanperuzzo.job_hunter.unit.infrastructure.scraper;

import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.GupyScraper;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("GupyScraper tests")
class GupyScraperTest {

    private ScraperPort gupyScraper;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        gupyScraper = new GupyScraper(baseUrl, List.of("desenvolvedor", "developer", "estagiário", "engenheiro de software"), List.of(), List.of(), 20, 5);
    }

    @Nested
    @DisplayName("Scenario 1: valid response with jobs")
    class ValidResponseTests {

        @Test
        @DisplayName("fetch should return list of correctly mapped Job objects when endpoint returns valid JSON")
        void fetch_whenValidResponse_shouldReturnMappedJobs() {
            String jsonResponse = """
                {
                  "data": [
                    {
                      "id": 12345,
                      "name": "Desenvolvedor Java Júnior",
                      "careerPageName": "CompanyX",
                      "jobUrl": "https://company.gupy.io/jobs/12345",
                      "publishedDate": "2025-03-10T14:00:00.000Z",
                      "description": "We are looking for a developer..."
                    }
                  ]
                }
                """;

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson(jsonResponse)));

            List<Job> jobs = gupyScraper.fetch();

            assertEquals(1, jobs.size());
            Job job = jobs.get(0);
            assertEquals("Desenvolvedor Java Júnior", job.title());
            assertEquals("CompanyX", job.company());
            assertEquals("https://company.gupy.io/jobs/12345", job.url());
            assertEquals(LocalDate.of(2025, 3, 10), job.postedAt());
            assertNotNull(job.description());
            assertEquals("We are looking for a developer...", job.description());
        }
    }

    @Nested
    @DisplayName("Scenario 2: response with empty list")
    class EmptyListTests {

        @Test
        @DisplayName("fetch should return empty list when endpoint returns data: []")
        void fetch_whenEmptyList_shouldReturnEmptyList() {
            String jsonResponse = """
                { "data": [] }
                """;

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson(jsonResponse)));

            List<Job> jobs = gupyScraper.fetch();

            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 3: request timeout")
    class TimeoutTests {

        @Test
        @DisplayName("fetch should throw ScraperException when request times out")
        void fetch_whenTimeout_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("{ \"data\": [] }").withFixedDelay(6000)));

            ScraperException exception = assertThrows(ScraperException.class,
                    () -> gupyScraper.fetch());

            assertTrue(exception.getMessage().toLowerCase().contains("timed out") ||
                       exception.getMessage().toLowerCase().contains("timeout"));
        }
    }

    @Nested
    @DisplayName("Scenario 4: HTTP 4xx or 5xx response")
    class HttpErrorTests {

        @Test
        @DisplayName("fetch should throw ScraperException when endpoint returns HTTP 500")
        void fetch_whenHttp500_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(serverError()));

            ScraperException exception = assertThrows(ScraperException.class,
                    () -> gupyScraper.fetch());

            assertTrue(exception.getMessage().contains("500"));
        }

        @Test
        @DisplayName("fetch should throw ScraperException when endpoint returns HTTP 404")
        void fetch_whenHttp404_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(notFound()));

            ScraperException exception = assertThrows(ScraperException.class,
                    () -> gupyScraper.fetch());

            assertTrue(exception.getMessage().contains("404"));
        }
    }

    @Nested
    @DisplayName("Scenario 5: malformed JSON")
    class MalformedJsonTests {

        @Test
        @DisplayName("fetch should throw ScraperException when endpoint returns invalid JSON")
        void fetch_whenMalformedJson_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("not valid json")));

            assertThrows(ScraperException.class, () -> gupyScraper.fetch());
        }
    }
}
