package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GreenhouseProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * All scenarios go through the provider's real mapper (no test-side mirror): the
 * production constructor wires {@code GreenhouseProvider.mapNode}, keeping the
 * mapping logic single-sourced. WorkModel assertions use real Greenhouse
 * spelling ("Híbrido, São Paulo") so accent handling is actually exercised
 * (PR#84 review P1-1).
 */
@ExtendWith(WireMockExtension.class)
@DisplayName("GreenhouseProvider tests")
class GreenhouseProviderTest {

    private String baseUrl;
    private GreenhouseProvider provider;
    private ExponentialBackoffRetry retry;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        provider = new GreenhouseProvider(baseUrl, 5, List.of("stone"), retry);
    }

    @Nested
    @DisplayName("Scenario 1: valid board response")
    class ValidResponse {

        @Test
        @DisplayName("extract should return mapped RawJob list with workModel inferred from real accented location")
        void extract_whenValidResponse_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [
                          {
                            "title": "Desenvolvedor Júnior",
                            "company_name": "Stone - Linkedin",
                            "absolute_url": "https://boards.greenhouse.io/stone/jobs/123",
                            "first_published": "2026-07-01T14:00:00.000Z",
                            "content": "<p>We need a developer</p>",
                            "location": {"name": "Híbrido, São Paulo"}
                          }
                        ]}
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("Desenvolvedor Júnior", job.title());
            assertEquals("Stone", job.company());
            assertEquals("https://boards.greenhouse.io/stone/jobs/123", job.url());
            assertEquals("2026-07-01", job.rawDate());
            assertEquals("Híbrido, São Paulo", job.location());
            assertEquals("Híbrido", job.workModel(),
                    "accented 'Híbrido' in the location must still infer Híbrido (PR#84 P1-1)");
            assertEquals("greenhouse", job.source());
        }
    }

    @Nested
    @DisplayName("Scenario 2: deduplication across boards")
    class Deduplication {

        @Test
        @DisplayName("extract should deduplicate by URL across boards")
        void extract_whenSameUrlAcrossBoards_shouldDeduplicate() {
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [{"title": "Dev Java", "absolute_url": "https://a.com/1", "first_published": "2026-07-01"}]}
                        """)));
            stubFor(get(urlPathEqualTo("/v1/boards/gitlab/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [{"title": "Dev Java", "absolute_url": "https://a.com/1", "first_published": "2026-07-01"}]}
                        """)));

            var dedupProvider = new GreenhouseProvider(baseUrl, 5, List.of("stone", "gitlab"), retry);
            var jobs = dedupProvider.extract();
            assertEquals(1, jobs.size());
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty board")
    class EmptyBoard {

        @Test
        @DisplayName("extract should return empty list when no jobs")
        void extract_whenEmptyJobs_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("{\"jobs\": []}")));

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
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(notFound()));
            stubFor(get(urlPathEqualTo("/v1/boards/gitlab/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [{"title": "Dev Java", "absolute_url": "https://a.com/1", "first_published": "2026-07-01"}]}
                        """)));

            var multiProvider = new GreenhouseProvider(baseUrl, 5, List.of("stone", "gitlab"), retry);
            var jobs = multiProvider.extract();

            // Dead board skipped, live board still fetched → 1 job, never a failure.
            assertEquals(1, jobs.size());
            assertEquals("Dev Java", jobs.get(0).title());
        }

        @Test
        @DisplayName("extract should return empty list when the only board is a 404")
        void extract_whenOnlyBoardIs404_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(notFound()));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty(), "a dead board must never fail the fetch");
        }
    }

    @Nested
    @DisplayName("Scenario 5: 429 retry then skip")
    class TooManyRequests {

        @Test
        @DisplayName("extract should return empty list when board keeps answering 429")
        void extract_when429Exhausted_shouldSkipBoard() {
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(serverError().withStatus(429).withHeader("Retry-After", "1")
                            .withBody("{\"error\": \"rate limited\"}")));

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
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [
                          {"title": "", "absolute_url": "https://a.com/1", "first_published": "2026-07-01"},
                          {"title": "No URL", "absolute_url": "", "first_published": "2026-07-01"}
                        ]}
                        """)));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }

        @Test
        @DisplayName("real mapper should strip company suffix and double-decode content")
        void realMapper_whenSuffixAndEncodedContent_shouldStripAndDecode() {
            // Real-Greenhouse content is HTML double-encoded (&amp;lt;...&amp;gt;): decode
            // twice then strip tags, exactly like docs/specs/ats-provider.md.
            var realMapperProvider = new GreenhouseProvider(baseUrl, 5, List.of("stone"), retry);

            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [{
                          "title": "Desenvolvedor",
                          "company_name": "Stone - Linkedin",
                          "absolute_url": "https://boards.greenhouse.io/stone/jobs/999",
                          "first_published": "2026-07-01T00:00:00.000Z",
                          "content": "&amp;lt;p&amp;gt;Estamos procurando &amp;amp; desenvolvedores&amp;lt;/p&amp;gt;",
                          "location": {"name": "Remoto"}
                        }]}
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("Stone", job.company(), "company suffix after ' - ' must be stripped");
            assertEquals("Estamos procurando & desenvolvedores", job.description(),
                    "HTML must be double-decoded then tags stripped");
            assertEquals("Remoto", job.workModel(), "location containing remoto infers Remoto");
        }

        @Test
        @DisplayName("real mapper should leave workModel null when no remote or hybrid hint is present")
        void realMapper_whenNoRemoteOrHybridHint_shouldKeepWorkModelNull() {
            stubFor(get(urlPathEqualTo("/v1/boards/stone/jobs"))
                    .withQueryParam("content", equalTo("true"))
                    .willReturn(okJson("""
                        {"jobs": [{
                          "title": "Analista",
                          "absolute_url": "https://boards.greenhouse.io/stone/jobs/1000",
                          "first_published": "2026-07-01T00:00:00.000Z",
                          "content": "&amp;lt;p&amp;gt;Vaga presencial em São Paulo.&amp;lt;/p&amp;gt;",
                          "location": {"name": "São Paulo"}
                        }]}
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());
            assertNull(jobs.get(0).workModel(),
                    "unknown work model must never block the job — null workModel is valid");
        }
    }
}