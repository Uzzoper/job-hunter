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
                List.of("106057199", "102927786", "105972731", "105906364"),
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
        @DisplayName("extract should forward only the first configured geo ID to the scraper service")
        void extract_whenGeoIdsConfigured_shouldForwardOnlyFirstGeoId() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .withQueryParam("geoId", equalTo("106057199"))
                    .willReturn(okJson("""
                        {
                          "success": true,
                          "data": []
                        }
                        """)));

            var jobs = client.extract();

            assertTrue(jobs.isEmpty());
            verify(0, getRequestedFor(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("geoId", equalTo("102927786")));
        }

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
        @DisplayName("extract should skip the keyword and return an empty list on an empty response body")
        void extract_whenEmptyResponse_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            assertTrue(client.extract().isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 3: HTTP 429 rate limited")
    class Http429 {

        @Test
        @DisplayName("extract should skip the keyword and return an empty list on HTTP 429")
        void extract_whenServiceReturns429_shouldReturnEmptyList() {
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

            assertTrue(client.extract().isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 4: HTTP 503 service unavailable")
    class Http503 {

        @Test
        @DisplayName("extract should skip the keyword and return an empty list on HTTP 503")
        void extract_whenServiceReturns503_shouldReturnEmptyList() {
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

            assertTrue(client.extract().isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 5: connection timeout")
    class ConnectionTimeout {

        @Test
        @DisplayName("extract should skip the keyword and return an empty list on connection timeout")
        void extract_whenConnectionTimeout_shouldReturnEmptyList() {
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

            assertTrue(client.extract().isEmpty());
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

    @Nested
    @DisplayName("Per-keyword query loop (production defect: only the first configured keyword was sent)")
    class MultiKeywordLoop {

        private LinkedInScraperClient clientWith(List<String> keywords, int maxJobs) {
            return new LinkedInScraperClient(new LinkedInScraperProperties(
                    true,
                    "service",
                    baseUrl,
                    30,
                    5,
                    maxJobs,
                    "https://www.linkedin.com",
                    keywords,
                    "Brazil",
                    List.of("106057199"),
                    List.of("entry_level"),
                    List.of("remote"),
                    "past_week",
                    1,
                    0,
                    "https://www.linkedin.com/jobs/view/"
            ));
        }

        @Test
        @DisplayName("extract should query the scraper service once per configured keyword, not only the first one")
        void extract_whenMultipleKeywords_shouldQueryServicePerKeyword() {
            client = clientWith(List.of("desenvolvedor", "junior"), 25);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "Desenvolvedor Sênior", "company": "A",
                                  "location": "São Paulo, SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("junior"))
                    .withQueryParam("location", equalTo("Brazil"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "2", "title": "Desenvolvedor Júnior", "company": "B",
                                  "location": "Rio de Janeiro, RJ", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            assertEquals(2, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().contains("Sênior")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().contains("Júnior")));
            verify(getRequestedFor(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor")));
            verify(getRequestedFor(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("junior")));
        }

        @Test
        @DisplayName("extract should deduplicate identical job URLs returned by different keywords")
        void extract_whenOverlappingUrlsAcrossKeywords_shouldDedupeByUrl() {
            client = clientWith(List.of("dev", "java"), 25);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("dev"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "42", "title": "Desenvolvedor", "company": "Shared",
                                  "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "10", "title": "Dev Pleno", "company": "Co",
                                  "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("java"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "42", "title": "Desenvolvedor", "company": "Shared",
                                  "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "20", "title": "Java Analyst", "company": "Co",
                                  "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            assertEquals(3, jobs.size());
            assertEquals(1, jobs.stream()
                    .filter(j -> j.metadata().get("jobId").equals("42"))
                    .count());
            assertEquals(1, jobs.stream()
                    .filter(j -> j.metadata().get("jobId").equals("10"))
                    .count());
            assertEquals(1, jobs.stream()
                    .filter(j -> j.metadata().get("jobId").equals("20"))
                    .count());
        }

        @Test
        @DisplayName("extract should keep earlier keyword results when a later keyword is rate-limited")
        void extract_whenMidLoopKeywordRateLimited_shouldKeepEarlierKeywordResults() {
            client = clientWith(List.of("desenvolvedor", "junior"), 25);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "Desenvolvedor Sênior", "company": "A",
                                  "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("junior"))
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

            List<RawJob> jobs = client.extract();

            assertEquals(1, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().contains("Sênior")));
            // the rate-limited keyword must still have been attempted
            verify(getRequestedFor(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("junior")));
        }

        @Test
        @DisplayName("extract should cap each keyword at its quota so the merged total never exceeds maxJobs")
        void extract_whenAllKeywordsExceedQuota_shouldNeverExceedMaxJobs() {
            // quota = ceil(maxJobs / keywords.size()) = ceil(4 / 2) = 2 per keyword
            client = clientWith(List.of("a", "b"), 4);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("a"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "A-1", "company": "A",
                                  "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "2", "title": "A-2", "company": "A",
                                  "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "3", "title": "A-3", "company": "A",
                                  "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "4", "title": "A-4", "company": "A",
                                  "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("b"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "10", "title": "B-1", "company": "B",
                                  "location": "RJ", "postedAt": "", "summary": "" },
                                { "id": "11", "title": "B-2", "company": "B",
                                  "location": "RJ", "postedAt": "", "summary": "" },
                                { "id": "12", "title": "B-3", "company": "B",
                                  "location": "RJ", "postedAt": "", "summary": "" },
                                { "id": "13", "title": "B-4", "company": "B",
                                  "location": "RJ", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            // each keyword keeps exactly its quota (first 2 of each batch, ordered pass);
            // "b" IS queried because "a" alone (2 jobs) did not fill the total cap of 4
            assertEquals(4, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("A-1")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("A-2")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("B-1")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("B-2")));
            assertTrue(jobs.stream().noneMatch(j -> j.title().equals("A-3")));
            assertTrue(jobs.stream().noneMatch(j -> j.title().equals("B-3")));
            verify(getRequestedFor(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("b")));
        }

        @Test
        @DisplayName("extract should stop querying further keywords once the merged results reach maxJobs")
        void extract_whenCapReachedBeforeLaterKeyword_shouldStopFurtherSearches() {
            // quota = ceil(2 / 3) = 1 per keyword: "a" and "b" each take their share
            // and reach the total cap, so the third keyword is never searched
            client = clientWith(List.of("a", "b", "c"), 2);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("a"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "Primeiro", "company": "A",
                                  "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("b"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "2", "title": "Segundo", "company": "B",
                                  "location": "RJ", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            // "c" is left stubbed so this test isolates the cap-stop from keyword
            // failures: a regression to always-run would still fetch it and trip
            // the verify(0) below.
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("c"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "3", "title": "Terceiro", "company": "C",
                                  "location": "MG", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            assertEquals(2, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Primeiro")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Segundo")));
            assertTrue(jobs.stream().noneMatch(j -> j.title().equals("Terceiro")));
            verify(0, getRequestedFor(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("c")));
        }

        @Test
        @DisplayName("extract should cap a fat first keyword at its quota so later keywords still contribute")
        void extract_whenFatFirstKeywordExceedsQuota_shouldNotStarveLaterKeywords() {
            // quota = ceil(9 / 3) = 3 per keyword; "fat" returns 6 but keeps only its first 3
            client = clientWith(List.of("fat", "thin1", "thin2"), 9);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("fat"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "Fat-1", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "2", "title": "Fat-2", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "3", "title": "Fat-3", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "4", "title": "Fat-4", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "5", "title": "Fat-5", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "6", "title": "Fat-6", "company": "A", "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("thin1"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "10", "title": "Thin-1", "company": "B", "location": "RJ", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("thin2"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "20", "title": "Thin-2", "company": "C", "location": "MG", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            assertEquals(5, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Fat-1")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Fat-3")));
            assertTrue(jobs.stream().noneMatch(j -> j.title().equals("Fat-4")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Thin-1")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Thin-2")));
        }

        @Test
        @DisplayName("extract should keep every job a thin keyword contributes when it returns less than its quota")
        void extract_whenThinKeywordReturnsLessThanQuota_shouldContributeAllItHas() {
            // quota = ceil(10 / 2) = 5 per keyword; neither batch reaches its quota
            client = clientWith(List.of("thin", "sparse"), 10);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("thin"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "Thin-A", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "2", "title": "Thin-B", "company": "A", "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));
            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("sparse"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "10", "title": "Sparse-1", "company": "B", "location": "RJ", "postedAt": "", "summary": "" },
                                { "id": "11", "title": "Sparse-2", "company": "B", "location": "RJ", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            assertEquals(4, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Thin-A")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Thin-B")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Sparse-1")));
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Sparse-2")));
        }

        @Test
        @DisplayName("extract should keep legacy behavior for a single keyword (quota equals maxJobs)")
        void extract_whenSingleKeyword_shouldKeepLegacyCapBehavior() {
            client = clientWith(List.of("dev"), 3);

            stubFor(get(urlPathEqualTo("/api/jobs"))
                    .withQueryParam("keywords", equalTo("dev"))
                    .willReturn(okJson("""
                            {
                              "success": true,
                              "data": [
                                { "id": "1", "title": "Dev-1", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "2", "title": "Dev-2", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "3", "title": "Dev-3", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "4", "title": "Dev-4", "company": "A", "location": "SP", "postedAt": "", "summary": "" },
                                { "id": "5", "title": "Dev-5", "company": "A", "location": "SP", "postedAt": "", "summary": "" }
                              ]
                            }
                            """)));

            List<RawJob> jobs = client.extract();

            assertEquals(3, jobs.size());
            assertTrue(jobs.stream().anyMatch(j -> j.title().equals("Dev-1")));
            assertTrue(jobs.stream().noneMatch(j -> j.title().equals("Dev-4")));
        }
    }
}
