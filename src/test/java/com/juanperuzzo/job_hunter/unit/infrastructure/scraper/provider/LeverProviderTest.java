package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.LeverProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("LeverProvider tests")
class LeverProviderTest {

    private String baseUrl;
    private LeverProvider provider;
    private ExponentialBackoffRetry retry;
    private RestApiStrategy apiStrategy;
    private Map<String, String> displayNames;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        displayNames = Map.of("dlocal", "dLocal", "metabase", "Metabase");
        // Lever's postings API returns a top-level array → jsonPath "" (root).
        apiStrategy = new RestApiStrategy("lever", baseUrl, 5, "", LeverProviderTest::mapNode);
        provider = new LeverProvider("lever", apiStrategy, retry, List.of("dlocal"), displayNames, 100, 10);
    }

    /** Test-side mapper mirroring the provider's field mapping (see LeverProvider.mapNode). */
    private static RawJob mapNode(JsonNode node) {
        var title = node.path("text").asText("");
        var url = node.path("hostedUrl").asText("");
        if (title.isBlank() || url.isBlank()) return null;
        var createdAt = node.path("createdAt").asLong(-1);
        var rawDate = createdAt >= 0
                ? Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString()
                : null;
        var workModel = inferWorkModel(node);
        return new RawJob(title, null, url, node.path("descriptionPlain").asText(null), rawDate,
                node.path("categories").path("location").asText(null), workModel, "lever", new HashMap<>());
    }

    private static String inferWorkModel(JsonNode node) {
        var workplaceType = node.path("workplaceType").asText("").toLowerCase();
        if ("remote".equals(workplaceType)) return "Remoto";
        if ("hybrid".equals(workplaceType)) return "Híbrido";
        return null;
    }

    @Nested
    @DisplayName("Scenario 1: valid board response")
    class ValidResponse {

        @Test
        @DisplayName("extract should return mapped RawJob list")
        void extract_whenValidResponse_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [
                          {
                            "text": "Desenvolvedor",
                            "hostedUrl": "https://jobs.lever.co/dlocal/abc",
                            "createdAt": 1750000000000,
                            "descriptionPlain": "Backend role",
                            "categories": {"location": "Sao Paulo (Hybrid)", "team": "Engineering"},
                            "workplaceType": "Hybrid"
                          }
                        ]
                        """)));

            var jobs = provider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("Desenvolvedor", job.title());
            assertEquals("https://jobs.lever.co/dlocal/abc", job.url());
            assertEquals("2025-06-15", job.rawDate(), "createdAt epoch millis must convert to UTC date");
            assertEquals("Sao Paulo (Hybrid)", job.location());
            assertEquals("lever", job.source());
        }
    }

    @Nested
    @DisplayName("Scenario 2: pagination")
    class Pagination {

        @Test
        @DisplayName("extract should fetch next page while the response is a full page")
        void extract_whenFullPage_shouldFetchNextPage() {
            var pageProvider = new LeverProvider("lever", apiStrategy, retry, List.of("metabase"), displayNames, 1, 10);

            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("0"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev 1", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("1"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev 2", "hostedUrl": "https://jobs.lever.co/metabase/2",
                          "createdAt": 1750000000000, "descriptionPlain": "B"}]
                        """)));
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("2"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("[]")));

            var jobs = pageProvider.extract();
            assertEquals(2, jobs.size());
            assertEquals("Dev 1", jobs.get(0).title());
            assertEquals("Dev 2", jobs.get(1).title());
        }

        @Test
        @DisplayName("extract should deduplicate by URL across pages")
        void extract_whenSameUrlAcrossPages_shouldDeduplicate() {
            var pageProvider = new LeverProvider("lever", apiStrategy, retry, List.of("metabase"), displayNames, 1, 10);

            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("0"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("1"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev again", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("2"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("[]")));

            var jobs = pageProvider.extract();
            assertEquals(1, jobs.size(), "same URL on a later page must not duplicate");
        }
    }

    @Nested
    @DisplayName("Scenario 2b: pagination guards (PR#84 review P2-a)")
    class PaginationGuards {

        @Test
        @DisplayName("extract should stop paginating after the max-pages cap is reached")
        void extract_whenFullPagesExceedMaxPages_shouldStopAtCap() {
            var cappedProvider = new LeverProvider("lever", apiStrategy, retry, List.of("metabase"), displayNames, 1, 2);

            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("0"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev 1", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("1"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev 2", "hostedUrl": "https://jobs.lever.co/metabase/2",
                          "createdAt": 1750000000000, "descriptionPlain": "B"}]
                        """)));

            var jobs = cappedProvider.extract();
            assertEquals(2, jobs.size(), "both full pages within the cap must be fetched");
            verify(2, getRequestedFor(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json")));
        }

        @Test
        @DisplayName("extract should stop when a full page yields zero new URLs (API ignoring skip)")
        void extract_whenFullPageHasNoNewUrls_shouldStopPagination() {
            var guardedProvider = new LeverProvider("lever", apiStrategy, retry, List.of("metabase"), displayNames, 1, 10);

            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("0"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));
            // API ignores skip: page 1 is "full" again but repeats the same URL.
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .withQueryParam("skip", equalTo("1"))
                    .withQueryParam("limit", equalTo("1"))
                    .willReturn(okJson("""
                        [{"text": "Dev", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));

            var jobs = guardedProvider.extract();
            assertEquals(1, jobs.size(), "no new URLs on a full page must stop the loop");
            verify(2, getRequestedFor(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json")));
        }
    }

    @Nested
    @DisplayName("Scenario 3: workModel mapping")
    class WorkModel {

        @Test
        @DisplayName("extract should map workplaceType remote and hybrid")
        void extract_whenWorkplaceType_shouldMapWorkModel() {
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [
                          {"text": "Remote Dev", "hostedUrl": "https://jobs.lever.co/dlocal/1",
                           "createdAt": 1750000000000, "descriptionPlain": "R", "workplaceType": "remote"},
                          {"text": "Hybrid Dev", "hostedUrl": "https://jobs.lever.co/dlocal/2",
                           "createdAt": 1750000000000, "descriptionPlain": "H", "workplaceType": "hybrid"},
                          {"text": "OnSite Dev", "hostedUrl": "https://jobs.lever.co/dlocal/3",
                           "createdAt": 1750000000000, "descriptionPlain": "O", "workplaceType": "on-site"}
                        ]
                        """)));

            var jobs = provider.extract();
            assertEquals(3, jobs.size());
            assertEquals("Remoto", byUrl(jobs, "https://jobs.lever.co/dlocal/1").workModel());
            assertEquals("Híbrido", byUrl(jobs, "https://jobs.lever.co/dlocal/2").workModel());
            assertNull(byUrl(jobs, "https://jobs.lever.co/dlocal/3").workModel(),
                    "on-site/unspecified must map to null");
        }

        /** Extract deduplicates into a HashMap — look jobs up by URL instead of position. */
        private static RawJob byUrl(List<RawJob> jobs, String url) {
            return jobs.stream().filter(j -> url.equals(j.url())).findFirst().orElseThrow();
        }
    }

    @Nested
    @DisplayName("Scenario 4: empty board")
    class EmptyBoard {

        @Test
        @DisplayName("extract should return empty list when no jobs")
        void extract_whenEmpty_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("[]")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 5: 404 board is skipped")
    class NotFound {

        @Test
        @DisplayName("extract should skip a dead board and return partial results")
        void extract_when404_shouldSkipBoardAndReturnOtherBoards() {
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(notFound()));
            stubFor(get(urlPathEqualTo("/v0/postings/metabase"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [{"text": "Dev", "hostedUrl": "https://jobs.lever.co/metabase/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));

            var multiProvider = new LeverProvider("lever", apiStrategy, retry, List.of("dlocal", "metabase"), displayNames, 100, 10);
            var jobs = multiProvider.extract();

            assertEquals(1, jobs.size());
            assertEquals("Dev", jobs.get(0).title());
        }
    }

    @Nested
    @DisplayName("Scenario 6: 429 retry then skip")
    class TooManyRequests {

        @Test
        @DisplayName("extract should return empty list when board keeps answering 429")
        void extract_when429Exhausted_shouldSkipBoard() {
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(status(429).withHeader("Retry-After", "1")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 7: edge cases")
    class EdgeCases {

        @Test
        @DisplayName("extract should skip entries with blank title or URL")
        void extract_whenBlankFields_shouldSkip() {
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [
                          {"text": "", "hostedUrl": "https://a.com/1", "createdAt": 1750000000000},
                          {"text": "No URL", "hostedUrl": "", "createdAt": 1750000000000}
                        ]
                        """)));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }

        @Test
        @DisplayName("real mapper should resolve company from display-names config")
        void realMapper_whenSiteKnown_shouldSetCompanyFromDisplayNames() {
            var realMapperProvider = new LeverProvider(baseUrl, 5, List.of("dlocal"), displayNames, retry, 100, 10);

            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [{"text": "Dev", "hostedUrl": "https://jobs.lever.co/dlocal/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());
            assertEquals("dLocal", jobs.get(0).company(),
                    "Lever responses carry no company — must come from display-names config");
        }

        @Test
        @DisplayName("real mapper should fall back to the site token for an unlisted site")
        void realMapper_whenSiteNotInDisplayNames_shouldFallBackToToken() {
            var realMapperProvider = new LeverProvider(baseUrl, 5, List.of("dlocal", "freshworks"), displayNames, retry, 100, 10);

            // Stub both sites; "freshworks" is deliberately absent from
            // ats.display-names → its company must fall back to the token, never null.
            stubFor(get(urlPathEqualTo("/v0/postings/dlocal"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [{"text": "dLocal Dev", "hostedUrl": "https://jobs.lever.co/dlocal/1",
                          "createdAt": 1750000000000, "descriptionPlain": "A"}]
                        """)));
            stubFor(get(urlPathEqualTo("/v0/postings/freshworks"))
                    .withQueryParam("mode", equalTo("json"))
                    .willReturn(okJson("""
                        [{"text": "Freshworks Dev", "hostedUrl": "https://jobs.lever.co/freshworks/1",
                          "createdAt": 1750000000000, "descriptionPlain": "B"}]
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(2, jobs.size());
            assertEquals("dLocal", byUrl(jobs, "https://jobs.lever.co/dlocal/1").company());
            assertEquals("freshworks", byUrl(jobs, "https://jobs.lever.co/freshworks/1").company(),
                    "unlisted site must fall back to its token, never null (PR#84 P2-c)");
        }

        /** Extract deduplicates into a HashMap — look jobs up by URL instead of position. */
        private static RawJob byUrl(List<RawJob> jobs, String url) {
            return jobs.stream().filter(j -> url.equals(j.url())).findFirst().orElseThrow();
        }
    }
}