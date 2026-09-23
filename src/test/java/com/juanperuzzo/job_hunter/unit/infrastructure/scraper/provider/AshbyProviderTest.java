package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.AshbyProvider;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("AshbyProvider tests")
class AshbyProviderTest {

    private String baseUrl;
    private AshbyProvider provider;
    private ExponentialBackoffRetry retry;
    private RestApiStrategy apiStrategy;
    private Map<String, String> displayNames;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        displayNames = Map.of("nubank", "Nubank", "notion", "Notion");
        // Ashby's job-board API returns a top-level array → jsonPath "" (root).
        apiStrategy = new RestApiStrategy("ashby", baseUrl, 5, "", AshbyProviderTest::mapNode);
        provider = new AshbyProvider("ashby", apiStrategy, retry, List.of("nubank"), displayNames);
    }

    /** Test-side mapper mirroring the provider's field mapping (see AshbyProvider.mapNode). */
    private static RawJob mapNode(JsonNode node) {
        var title = node.path("title").asText("");
        var url = node.path("jobUrl").asText("");
        if (title.isBlank() || url.isBlank()) return null;
        var rawDate = node.path("publishedAt").asText(null);
        if (rawDate != null && rawDate.length() >= 10) rawDate = rawDate.substring(0, 10);
        var workModel = inferWorkModel(node);
        var metadata = new HashMap<String, String>();
        var employmentType = node.path("employmentType").asText("");
        if ("Intern".equalsIgnoreCase(employmentType)) {
            metadata.put("atsEmploymentType", employmentType);
        }
        return new RawJob(title, null, url, node.path("descriptionPlain").asText(null), rawDate,
                node.path("location").asText(null), workModel, "ashby", metadata);
    }

    private static String inferWorkModel(JsonNode node) {
        var isRemote = node.path("isRemote").asBoolean(false);
        var workplaceType = node.path("workplaceType").asText("");
        if (isRemote || "Remote".equalsIgnoreCase(workplaceType)) return "Remoto";
        if ("Hybrid".equalsIgnoreCase(workplaceType)) return "Híbrido";
        return null;
    }

    @Nested
    @DisplayName("Scenario 1: valid board response")
    class ValidResponse {

        @Test
        @DisplayName("extract should return mapped RawJob list")
        void extract_whenValidResponse_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        [
                          {
                            "title": "Software Engineer",
                            "jobUrl": "https://jobs.ashbyhq.com/nubank/abc123",
                            "publishedAt": "2026-07-01T10:00:00.000Z",
                            "descriptionPlain": "Backend role",
                            "location": "São Paulo",
                            "isRemote": false,
                            "workplaceType": "OnSite"
                          }
                        ]
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("Software Engineer", job.title());
            assertEquals("https://jobs.ashbyhq.com/nubank/abc123", job.url());
            assertEquals("2026-07-01", job.rawDate());
            assertEquals("São Paulo", job.location());
            assertEquals("ashby", job.source());
        }
    }

    @Nested
    @DisplayName("Scenario 2: workModel and employmentType mapping")
    class WorkModelAndEmploymentType {

        @Test
        @DisplayName("extract should map Remote workplaceType and forward Intern employmentType")
        void extract_whenRemoteAndIntern_shouldMapWorkModelAndMetadata() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        [
                          {
                            "title": "Intern",
                            "jobUrl": "https://jobs.ashbyhq.com/nubank/intern-1",
                            "publishedAt": "2026-07-01T10:00:00.000Z",
                            "descriptionPlain": "Estágio em engenharia",
                            "location": "Remote",
                            "isRemote": true,
                            "workplaceType": "Remote",
                            "employmentType": "Intern"
                          }
                        ]
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("Remoto", job.workModel());
            assertEquals("Intern", job.metadata().get("atsEmploymentType"),
                    "Intern employmentType must be forwarded as a junior hint");
        }

        @Test
        @DisplayName("extract should map Hybrid workplaceType to Híbrido")
        void extract_whenHybrid_shouldMapWorkModelToHibrido() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        [
                          {
                            "title": "Dev",
                            "jobUrl": "https://jobs.ashbyhq.com/nubank/hybrid-1",
                            "publishedAt": "2026-07-01T10:00:00.000Z",
                            "descriptionPlain": "Role",
                            "location": "São Paulo",
                            "isRemote": false,
                            "workplaceType": "Hybrid"
                          }
                        ]
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());
            assertEquals("Híbrido", jobs.get(0).workModel());
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty board")
    class EmptyBoard {

        @Test
        @DisplayName("extract should return empty list when no jobs")
        void extract_whenEmptyBoard_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("[]")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 4: 404 board is skipped")
    class NotFound {

        @Test
        @DisplayName("extract should skip a dead board and return partial results")
        void extract_when404_shouldSkipBoardAndReturnOtherBoards() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(notFound()));
            stubFor(get(urlPathEqualTo("/posting-api/job-board/notion"))
                    .willReturn(okJson("""
                        [
                          {"title": "Dev", "jobUrl": "https://jobs.ashbyhq.com/notion/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]
                        """)));

            var multiProvider = new AshbyProvider("ashby", apiStrategy, retry, List.of("nubank", "notion"), displayNames);
            var jobs = multiProvider.extract();

            assertEquals(1, jobs.size());
            assertEquals("Dev", jobs.get(0).title());
        }
    }

    @Nested
    @DisplayName("Scenario 5: 429 retry then skip")
    class TooManyRequests {

        @Test
        @DisplayName("extract should return empty list when board keeps answering 429")
        void extract_when429Exhausted_shouldSkipBoard() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(status(429).withHeader("Retry-After", "1")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 6: edge cases")
    class EdgeCases {

        @Test
        @DisplayName("extract should skip entries with blank title or URL")
        void extract_whenBlankFields_shouldSkip() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        [
                          {"title": "", "jobUrl": "https://a.com/1", "publishedAt": "2026-07-01"},
                          {"title": "No URL", "jobUrl": "", "publishedAt": "2026-07-01"}
                        ]
                        """)));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }

        @Test
        @DisplayName("real mapper should resolve company from display-names config")
        void realMapper_whenBoardKnown_shouldSetCompanyFromDisplayNames() {
            var realMapperProvider = new AshbyProvider(baseUrl, 5, List.of("nubank"), displayNames, retry);

            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        [
                          {"title": "Dev", "jobUrl": "https://jobs.ashbyhq.com/nubank/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());
            assertEquals("Nubank", jobs.get(0).company(),
                    "Ashby responses carry no company — must come from display-names config");
        }

        @Test
        @DisplayName("real mapper should fall back to the board token for an unlisted board")
        void realMapper_whenBoardNotInDisplayNames_shouldFallBackToToken() {
            var realMapperProvider = new AshbyProvider(baseUrl, 5, List.of("nubank", "unlisted"), displayNames, retry);

            // Stub both boards; the "unlisted" board is deliberately absent from
            // ats.display-names → its company must fall back to the token, never null.
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        [
                          {"title": "Nubank Dev", "jobUrl": "https://jobs.ashbyhq.com/nubank/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]
                        """)));
            stubFor(get(urlPathEqualTo("/posting-api/job-board/unlisted"))
                    .willReturn(okJson("""
                        [
                          {"title": "Unlisted Dev", "jobUrl": "https://jobs.ashbyhq.com/unlisted/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(2, jobs.size());
            assertEquals("Nubank", byUrl(jobs, "https://jobs.ashbyhq.com/nubank/1").company());
            assertEquals("unlisted", byUrl(jobs, "https://jobs.ashbyhq.com/unlisted/1").company(),
                    "unlisted board must fall back to its token, never null (PR#84 P2-c)");
        }

        /** Extract deduplicates into a HashMap — look jobs up by URL instead of position. */
        private static RawJob byUrl(List<RawJob> jobs, String url) {
            return jobs.stream().filter(j -> url.equals(j.url())).findFirst().orElseThrow();
        }
    }
}