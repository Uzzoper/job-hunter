package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.AshbyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * All scenarios go through the provider's real mapper and reflect the REAL Ashby
 * posting API envelope shape {@code {"apiVersion":"1","jobs":[...]}} — the previous
 * bare-array stubs encoded a wrong assumption that hid the object-envelope bug
 * (live-proven: 7/7 boards returned 0 jobs, PR#84 review follow-up).
 */
@ExtendWith(WireMockExtension.class)
@DisplayName("AshbyProvider tests")
class AshbyProviderTest {

    private String baseUrl;
    private AshbyProvider provider;
    private ExponentialBackoffRetry retry;
    private Map<String, String> displayNames;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        displayNames = Map.of("nubank", "Nubank", "notion", "Notion");
        provider = new AshbyProvider(baseUrl, 5, List.of("nubank"), displayNames, retry);
    }

    @Nested
    @DisplayName("Scenario 1: valid board response")
    class ValidResponse {

        @Test
        @DisplayName("extract should return mapped RawJob list from the envelope jobs array")
        void extract_whenValidResponse_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        {"apiVersion": "1", "jobs": [
                          {
                            "title": "Software Engineer",
                            "jobUrl": "https://jobs.ashbyhq.com/nubank/abc123",
                            "publishedAt": "2026-07-01T10:00:00.000Z",
                            "descriptionPlain": "Backend role",
                            "location": "São Paulo",
                            "isRemote": false,
                            "workplaceType": "OnSite"
                          }
                        ]}
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
                        {"apiVersion": "1", "jobs": [
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
                        ]}
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
                        {"apiVersion": "1", "jobs": [
                          {
                            "title": "Dev",
                            "jobUrl": "https://jobs.ashbyhq.com/nubank/hybrid-1",
                            "publishedAt": "2026-07-01T10:00:00.000Z",
                            "descriptionPlain": "Role",
                            "location": "São Paulo",
                            "isRemote": false,
                            "workplaceType": "Hybrid"
                          }
                        ]}
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
        @DisplayName("extract should return empty list when the envelope holds no jobs")
        void extract_whenEmptyBoard_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/posting-api/job-board/nubank"))
                    .willReturn(okJson("""
                        {"apiVersion": "1", "jobs": []}
                        """)));

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
                        {"apiVersion": "1", "jobs": [
                          {"title": "Dev", "jobUrl": "https://jobs.ashbyhq.com/notion/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]}
                        """)));

            var multiProvider = new AshbyProvider(baseUrl, 5, List.of("nubank", "notion"), displayNames, retry);
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
                        {"apiVersion": "1", "jobs": [
                          {"title": "", "jobUrl": "https://a.com/1", "publishedAt": "2026-07-01"},
                          {"title": "No URL", "jobUrl": "", "publishedAt": "2026-07-01"}
                        ]}
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
                        {"apiVersion": "1", "jobs": [
                          {"title": "Dev", "jobUrl": "https://jobs.ashbyhq.com/nubank/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]}
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
                        {"apiVersion": "1", "jobs": [
                          {"title": "Nubank Dev", "jobUrl": "https://jobs.ashbyhq.com/nubank/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]}
                        """)));
            stubFor(get(urlPathEqualTo("/posting-api/job-board/unlisted"))
                    .willReturn(okJson("""
                        {"apiVersion": "1", "jobs": [
                          {"title": "Unlisted Dev", "jobUrl": "https://jobs.ashbyhq.com/unlisted/1",
                           "publishedAt": "2026-07-01T10:00:00.000Z", "descriptionPlain": "Role"}
                        ]}
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