package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.config.LinkedInScraperProperties;
import com.juanperuzzo.job_hunter.infrastructure.scraper.client.LinkedInScraperClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("LinkedInScraperClient tests")
class LinkedInScraperClientTest {

    private String baseUrl;
    private LinkedInScraperClient client;
    private LinkedInScraperProperties properties;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        properties = new LinkedInScraperProperties(
                true,
                "service",
                baseUrl,
                30,
                5,
                25,
                "https://www.linkedin.com",
                List.of("desenvolvedor"),
                "Brazil",
                List.of("106057199"),
                List.of("entry_level"),
                List.of("remote"),
                "past_week",
                1,
                500,
                "https://www.linkedin.com/jobs/view/"
        );
        client = new LinkedInScraperClient(properties);
    }

    @Nested
    @DisplayName("Scenario 1: valid search response")
    class ValidSearchResponse {

        @Test
        @DisplayName("extract should return mapped RawJob list with source=linkedin")
        void extract_whenValidResponse_shouldReturnRawJobs() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": [
                            {
                              "id": "12345",
                              "title": "Desenvolvedor Java Júnior",
                              "company": "TechCo Solutions",
                              "location": "São Paulo, SP",
                              "postedAt": "2026-07-01T14:00:00.000Z",
                              "summary": ""
                            },
                            {
                              "id": "67890",
                              "title": "Desenvolvedor Python Júnior",
                              "company": "DataCo Brasil",
                              "location": "Rio de Janeiro, RJ",
                              "postedAt": "2026-06-28T10:30:00.000Z",
                              "summary": ""
                            }
                          ]
                        }
                        """)));

            List<RawJob> jobs = client.extract();

            assertEquals(2, jobs.size());

            RawJob job1 = jobs.get(0);
            assertEquals("linkedin", job1.source());
            assertEquals("Desenvolvedor Java Júnior", job1.title());
            assertEquals("TechCo Solutions", job1.company());
            assertEquals("https://www.linkedin.com/jobs/view/12345", job1.url());
            assertEquals("2026-07-01T14:00:00.000Z", job1.rawDate());
            assertEquals("São Paulo, SP", job1.location());
            assertEquals("", job1.description());
            assertNotNull(job1.metadata());
            assertEquals("12345", job1.metadata().get("jobId"));

            RawJob job2 = jobs.get(1);
            assertEquals("linkedin", job2.source());
            assertEquals("Desenvolvedor Python Júnior", job2.title());
            assertEquals("DataCo Brasil", job2.company());
            assertEquals("https://www.linkedin.com/jobs/view/67890", job2.url());
            assertEquals("2026-06-28T10:30:00.000Z", job2.rawDate());
            assertEquals("Rio de Janeiro, RJ", job2.location());
        }
    }

    @Nested
    @DisplayName("Scenario 2: empty search response")
    class EmptySearchResponse {

        @Test
        @DisplayName("extract should throw ScraperException when empty response body")
        void extract_whenEmptyResponse_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            var exception = assertThrows(ScraperException.class, () -> client.extract());
            assertTrue(exception.getMessage().contains("returned empty response"));
        }
    }

    @Nested
    @DisplayName("Scenario 3: HTTP 429 rate limited")
    class Http429 {

        @Test
        @DisplayName("extract should throw ScraperException with retry hint on HTTP 429")
        void extract_whenServiceReturns429_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(status(429)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                {
                                  "success": false,
                                  "error": {
                                    "code": "RATE_LIMITED",
                                    "message": "LinkedIn bot challenge detected. Please try again later."
                                  }
                                }
                                """)));

            ScraperException exception = assertThrows(ScraperException.class, () -> client.extract());
            assertTrue(exception.getMessage().contains("429") || exception.getMessage().contains("RATE_LIMITED"));
        }
    }

    @Nested
    @DisplayName("Scenario 4: HTTP 503 service unavailable")
    class Http503 {

        @Test
        @DisplayName("extract should throw ScraperException on HTTP 503")
        void extract_whenServiceReturns503_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(status(503)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                {
                                  "success": false,
                                  "error": {
                                    "code": "SERVICE_UNAVAILABLE",
                                    "message": "Browser is not ready"
                                  }
                                }
                                """)));

            assertThrows(ScraperException.class, () -> client.extract());
        }
    }

    @Nested
    @DisplayName("Scenario 5: connection timeout")
    class ConnectionTimeout {

        @Test
        @DisplayName("extract should throw ScraperException on connection timeout")
        void extract_whenConnectionTimeout_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay(40000)
                            .withBody("""
                                {
                                  "success": true,
                                  "data": []
                                }
                                """)));

            assertThrows(ScraperException.class, () -> client.extract());
        }
    }

    @Nested
    @DisplayName("Scenario 6: detail endpoint")
    class DetailEndpoint {

        @Test
        @DisplayName("extractDetail should return RawJob with description filled")
        void extractDetail_whenValidResponse_shouldReturnRawJobWithDescription() {
            stubFor(get(urlPathEqualTo("/api/jobs/12345"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": {
                            "id": "12345",
                            "title": "Desenvolvedor Java Júnior",
                            "company": "TechCo Solutions",
                            "location": "São Paulo, SP",
                            "postedAt": "2026-07-01T14:00:00.000Z",
                            "summary": "",
                            "description": "<p>Estamos buscando um Desenvolvedor Java Júnior para se juntar ao nosso time.</p><p>Requisitos:</p><ul><li>Java 11+</li><li>Spring Boot</li></ul>",
                            "requirements": ["Java 11+", "Spring Boot"],
                            "salary": null,
                            "jobType": "Full-time",
                            "seniority": "Entry level"
                          }
                        }
                        """)));

            RawJob job = client.extractDetail("12345");

            assertNotNull(job);
            assertEquals("linkedin", job.source());
            assertEquals("Desenvolvedor Java Júnior", job.title());
            assertEquals("TechCo Solutions", job.company());
            assertEquals("https://www.linkedin.com/jobs/view/12345", job.url());
            assertEquals("2026-07-01T14:00:00.000Z", job.rawDate());
            assertEquals("São Paulo, SP", job.location());
            assertTrue(job.description().contains("Desenvolvedor Java Júnior"));
            assertTrue(job.description().contains("Spring Boot"));
            assertEquals("12345", job.metadata().get("jobId"));
        }
    }

    @Nested
    @DisplayName("Scenario 7: detail endpoint 404")
    class DetailEndpoint404 {

        @Test
        @DisplayName("extractDetail should throw ScraperException on HTTP 404")
        void extractDetail_whenJobNotFound_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/api/jobs/99999"))
                    .willReturn(status(404)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                {
                                  "success": false,
                                  "error": {
                                    "code": "NOT_FOUND",
                                    "message": "Job not found"
                                  }
                                }
                                """)));

            assertThrows(ScraperException.class, () -> client.extractDetail("99999"));
        }
    }

    @Nested
    @DisplayName("Scenario 8: providerId")
    class ProviderId {

        @Test
        @DisplayName("providerId should return 'linkedin'")
        void providerId_shouldReturnLinkedin() {
            assertEquals("linkedin", client.providerId());
        }
    }

    @Nested
    @DisplayName("Scenario 9: detail enrichment")
    class DetailEnrichment {

        @Test
        @DisplayName("extract should return jobs with descriptions populated from detail endpoint")
        void extract_whenDetailSuccess_shouldEnrichDescriptions() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": [
                            {
                              "id": "12345",
                              "title": "Desenvolvedor Java Júnior",
                              "company": "TechCo Solutions",
                              "location": "São Paulo, SP",
                              "postedAt": "2026-07-01T14:00:00.000Z",
                              "summary": ""
                            },
                            {
                              "id": "67890",
                              "title": "Desenvolvedor Python Júnior",
                              "company": "DataCo Brasil",
                              "location": "Rio de Janeiro, RJ",
                              "postedAt": "2026-06-28T10:30:00.000Z",
                              "summary": ""
                            }
                          ]
                        }
                        """)));

            stubFor(get(urlPathEqualTo("/api/jobs/12345"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": {
                            "id": "12345",
                            "title": "Desenvolvedor Java Júnior",
                            "company": "TechCo Solutions",
                            "location": "São Paulo, SP",
                            "postedAt": "2026-07-01T14:00:00.000Z",
                            "summary": "",
                            "description": "<p>Estamos buscando um Desenvolvedor Java Júnior para se juntar ao nosso time.</p><p>Requisitos:</p><ul><li>Java 11+</li><li>Spring Boot</li></ul>",
                            "requirements": ["Java 11+", "Spring Boot"],
                            "salary": null,
                            "jobType": "Full-time",
                            "seniority": "Entry level"
                          }
                        }
                        """)));

            stubFor(get(urlPathEqualTo("/api/jobs/67890"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": {
                            "id": "67890",
                            "title": "Desenvolvedor Python Júnior",
                            "company": "DataCo Brasil",
                            "location": "Rio de Janeiro, RJ",
                            "postedAt": "2026-06-28T10:30:00.000Z",
                            "summary": "",
                            "description": "<p>Buscamos um Desenvolvedor Python Júnior para atuar com análise de dados.</p><p>Requisitos:</p><ul><li>Python 3</li><li>Django</li></ul>",
                            "requirements": ["Python 3", "Django"],
                            "salary": null,
                            "jobType": "Full-time",
                            "seniority": "Entry level"
                          }
                        }
                        """)));

            List<RawJob> jobs = client.extract();

            assertEquals(2, jobs.size());

            RawJob job1 = jobs.get(0);
            assertEquals("Desenvolvedor Java Júnior", job1.title());
            assertTrue(job1.description().contains("Java 11+"));
            assertTrue(job1.description().contains("Spring Boot"));
            assertEquals("12345", job1.metadata().get("jobId"));

            RawJob job2 = jobs.get(1);
            assertEquals("Desenvolvedor Python Júnior", job2.title());
            assertTrue(job2.description().contains("Python 3"));
            assertTrue(job2.description().contains("Django"));
            assertEquals("67890", job2.metadata().get("jobId"));
        }

        @Test
        @DisplayName("extract should return all jobs even when some detail fetches fail")
        void extract_whenPartialDetailFailure_shouldKeepAllJobs() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": [
                            {
                              "id": "12345",
                              "title": "Desenvolvedor Java Júnior",
                              "company": "TechCo Solutions",
                              "location": "São Paulo, SP",
                              "postedAt": "2026-07-01T14:00:00.000Z",
                              "summary": ""
                            },
                            {
                              "id": "67890",
                              "title": "Desenvolvedor Python Júnior",
                              "company": "DataCo Brasil",
                              "location": "Rio de Janeiro, RJ",
                              "postedAt": "2026-06-28T10:30:00.000Z",
                              "summary": ""
                            }
                          ]
                        }
                        """)));

            stubFor(get(urlPathEqualTo("/api/jobs/12345"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": {
                            "id": "12345",
                            "title": "Desenvolvedor Java Júnior",
                            "company": "TechCo Solutions",
                            "location": "São Paulo, SP",
                            "postedAt": "2026-07-01T14:00:00.000Z",
                            "summary": "",
                            "description": "<p>Estamos buscando um Desenvolvedor Java Júnior para se juntar ao nosso time.</p>",
                            "requirements": ["Java 11+", "Spring Boot"],
                            "salary": null,
                            "jobType": "Full-time",
                            "seniority": "Entry level"
                          }
                        }
                        """)));

            stubFor(get(urlPathEqualTo("/api/jobs/67890"))
                    .willReturn(status(500)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                {
                                  "success": false,
                                  "error": {
                                    "code": "INTERNAL_ERROR",
                                    "message": "Failed to fetch job details"
                                  }
                                }
                                """)));

            List<RawJob> jobs = client.extract();

            assertEquals(2, jobs.size());

            RawJob job1 = jobs.get(0);
            assertEquals("Desenvolvedor Java Júnior", job1.title());
            assertTrue(job1.description().contains("Desenvolvedor Java Júnior"));

            RawJob job2 = jobs.get(1);
            assertEquals("Desenvolvedor Python Júnior", job2.title());
            assertEquals("", job2.description());
        }
    }
}