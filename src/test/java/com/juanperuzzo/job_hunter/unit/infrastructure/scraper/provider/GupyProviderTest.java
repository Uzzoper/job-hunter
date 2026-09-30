package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

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
                isRemote ? "Remoto" : null, "gupy", new HashMap<>());
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
    @DisplayName("Scenario 4: auth failure fast-path")
    class AuthFailure {

        @Test
        @DisplayName("extract should skip remaining keywords on 401 and return partial results")
        void extract_whenFirstKeywordReturns401_shouldSkipRemainingKeywordsAndReturnPartial() {
            // First keyword returns 200 with one job
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("dev"))
                    .willReturn(okJson("""
                        {"data": [{"name": "Dev Java", "jobUrl": "https://a.com/1", "publishedDate": "2026-07-01"}]}
                        """)));
            // Second keyword returns 401
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("java"))
                    .willReturn(unauthorized().withHeader("Content-Type", "application/json")
                            .withBody("{\"error\": \"Unauthorized\"}")));
            // Third keyword should never be called
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("python"))
                    .willReturn(okJson("""
                        {"data": [{"name": "Python Dev", "jobUrl": "https://a.com/3", "publishedDate": "2026-07-01"}]}
                        """)));

            var multiKeyRetry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
            var multiKeyStrategy = new RestApiStrategy("gupy", baseUrl, 5, "data", GupyProviderTest::mapNode);
            var multiKeyProvider = new GupyProvider("gupy", multiKeyStrategy, multiKeyRetry,
                    List.of("dev", "java", "python"), 20);

            var jobs = multiKeyProvider.extract();

            // First keyword succeeded → 1 job returned; second keyword hit 401 → loop broke;
            // third keyword was never attempted.
            assertEquals(1, jobs.size(), "should return partial results from keyword before 401");
            assertEquals("Dev Java", jobs.get(0).title());
        }

        @Test
        @DisplayName("extract should detect 401 by HTTP status and make a single attempt (no message matching, no retry)")
        void extract_when401_shouldDetectByStatusAndRetryOnlyOnce() {
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("dev"))
                    .willReturn(okJson("""
                        {"data": [{"name": "Dev Java", "jobUrl": "https://a.com/1", "publishedDate": "2026-07-01"}]}
                        """)));
            // Second keyword returns 401 — detection must come from the HTTP status, not the message
            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("java"))
                    .willReturn(unauthorized().withHeader("Content-Type", "application/json")
                            .withBody("{\"error\": \"Unauthorized\"}")));

            var provider = new GupyProvider("gupy", apiStrategy, retry, List.of("dev", "java"), 20);

            var jobs = provider.extract();

            // Partial results: the keyword before the 401 is returned
            assertEquals(1, jobs.size());
            assertEquals("Dev Java", jobs.get(0).title());

            // The 401 keyword must have been attempted exactly once (fail-fast, no retry)
            verify(1, getRequestedFor(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("java")));
        }
    }

    @Nested
    @DisplayName("Scenario 3: edge cases")
    class EdgeCases {

        @Test
        @DisplayName("extract should not store a portal careerPageUrl as companyWebsite - Scenario 10")
        void extract_whenCareerPageUrlIsPortal_shouldNotSetCompanyWebsite() {
            // Use the provider's real mapper (first constructor wires GupyProvider.mapNode)
            var realMapperProvider = new GupyProvider(baseUrl, 5, List.of("desenvolvedor"), 20, retry);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("desenvolvedor"))
                    .withQueryParam("limit", equalTo("20"))
                    .willReturn(okJson("""
                        {"data": [{
                          "name": "Dev Java",
                          "careerPageName": "TechCo",
                          "jobUrl": "https://techco.gupy.io/jobs/123",
                          "careerPageUrl": "https://techco.gupy.io",
                          "publishedDate": "2026-07-01",
                          "description": "Vaga"
                        }]}
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("https://techco.gupy.io/jobs/123", job.url(), "jobUrl should still win for url");
            assertNull(job.metadata().get("companyWebsite"),
                    "a portal careerPageUrl must never be stored as companyWebsite — Gupy's list API has no real company-site field");
        }

        @Test
        @DisplayName("extract should keep a non-portal companyWebsite")
        void extract_whenCompanyWebsiteNonPortal_shouldKeepIt() {
            // Use the provider's real mapper (first constructor wires GupyProvider.mapNode)
            var realMapperProvider = new GupyProvider(baseUrl, 5, List.of("desenvolvedor"), 20, retry);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("desenvolvedor"))
                    .withQueryParam("limit", equalTo("20"))
                    .willReturn(okJson("""
                        {"data": [{
                          "name": "Dev Java",
                          "careerPageName": "TechCo",
                          "jobUrl": "https://techco.gupy.io/jobs/123",
                          "careerPageUrl": "https://techco.com",
                          "publishedDate": "2026-07-01",
                          "description": "Vaga"
                        }]}
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("https://techco.com", job.metadata().get("companyWebsite"),
                    "a non-portal companyWebsite should pass through unchanged");
        }

        @Test
        @DisplayName("extract should not store a portal careerPageUrl whose host contains an underscore")
        void extract_whenCareerPageUrlHasUnderscoreHost_shouldNotSetCompanyWebsite() {
            // Regression: URI.getHost() returns null for underscore hosts (RFC 2396 registry
            // fallback), which previously bypassed the portal filter — see job 1874.
            var realMapperProvider = new GupyProvider(baseUrl, 5, List.of("desenvolvedor"), 20, retry);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("desenvolvedor"))
                    .withQueryParam("limit", equalTo("20"))
                    .willReturn(okJson("""
                        {"data": [{
                          "name": "Dev Java",
                          "careerPageName": "TechCo",
                          "jobUrl": "https://bbc_digital.gupy.io/jobs/123",
                          "careerPageUrl": "https://bbc_digital.gupy.io/eyJhbGciOiJ9",
                          "publishedDate": "2026-07-01",
                          "description": "Vaga"
                        }]}
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertNull(job.metadata().get("companyWebsite"),
                    "an underscore portal host must still be dropped as companyWebsite");
        }

        @Test
        @DisplayName("extract should not store a vaga-ja.com careerPageUrl as companyWebsite")
        void extract_whenCareerPageUrlIsVagaJaPortal_shouldNotSetCompanyWebsite() {
            // Live-verified: fresh Gupy fetch stored company_website on vaga-ja.com (same
            // job-board family as Gupy — pages carry ?jobBoardSource=gupy_portal).
            var realMapperProvider = new GupyProvider(baseUrl, 5, List.of("desenvolvedor"), 20, retry);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .withQueryParam("jobName", equalTo("desenvolvedor"))
                    .withQueryParam("limit", equalTo("20"))
                    .willReturn(okJson("""
                        {"data": [{
                          "name": "Dev Java",
                          "careerPageName": "VagaJá",
                          "jobUrl": "https://empresa.vaga-ja.com/jobs/123",
                          "careerPageUrl": "https://empresa.vaga-ja.com/eyJhbGciOiJ9",
                          "publishedDate": "2026-07-01",
                          "description": "Vaga"
                        }]}
                        """)));

            var jobs = realMapperProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertNull(job.metadata().get("companyWebsite"),
                    "a vaga-ja.com careerPageUrl must never be stored as companyWebsite");
        }

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

    @Nested
    @DisplayName("Scenario 11: detail-page company domains")
    class DetailPageCompanyDomains {

        /** Provider with detail-domain resolution enabled (no cap by default). */
        private GupyProvider detailProvider(int maxDetailDomains) {
            var detailRestClient = RestClient.builder().baseUrl(baseUrl).build();
            var detailRateLimiter = new TokenBucketRateLimiter(100, 10, java.util.Map.of());
            return new GupyProvider("gupy", apiStrategy, retry, List.of("desenvolvedor"), 20,
                    detailRestClient, detailRateLimiter, maxDetailDomains);
        }

        @Test
        @DisplayName("extract should attach the first eligible company link to all same-host jobs")
        void extract_whenDetailPageHasCompanyLink_shouldSetMetadataOnAllSameHostJobs() {
            var provider = detailProvider(100);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [
                          {"name": "Dev Java", "jobUrl": "%s/gupy/techco/jobs/1", "publishedDate": "2026-07-01"},
                          {"name": "Dev Spring", "jobUrl": "%s/gupy/techco/jobs/2", "publishedDate": "2026-07-01"}
                        ]}
                        """.formatted(baseUrl, baseUrl))));
            // Social link comes first in the page — it must be skipped, the company link wins.
            // Every job of the host is stubbed: extract() dedups via a HashMap, so the
            // "first" job URL of a host is not deterministic — but the host-level
            // contract is: exactly one detail fetch total, whichever URL.
            var detailPage = """
                    <html><body>
                      <a href="https://www.linkedin.com/company/techco">LinkedIn</a>
                      <a href="https://www.techco.com.br/">Site</a>
                    </body></html>
                    """;
            stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok(detailPage)));
            stubFor(get(urlEqualTo("/gupy/techco/jobs/2")).willReturn(ok(detailPage)));

            var jobs = provider.extract();

            assertEquals(2, jobs.size());
            assertEquals("https://www.techco.com.br", jobs.get(0).metadata().get("companyWebsite"),
                    "the detail-page company link must be attached (social links skipped)");
            assertEquals("https://www.techco.com.br", jobs.get(1).metadata().get("companyWebsite"),
                    "the resolved domain must be attached to every job of the same host");
            verify(1, getRequestedFor(urlMatching("/gupy/techco/jobs/[12]")));
        }

        @Test
        @DisplayName("extract should not set companyWebsite when the detail page has only portal/social links")
        void extract_whenDetailPageHasOnlyPortalOrSocialLinks_shouldNotSetCompanyWebsite() {
            var provider = detailProvider(100);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [
                          {"name": "Dev Java", "jobUrl": "%s/gupy/brand/jobs/1", "publishedDate": "2026-07-01"}
                        ]}
                        """.formatted(baseUrl))));
            stubFor(get(urlEqualTo("/gupy/brand/jobs/1")).willReturn(ok("""
                    <html><body>
                      <a href="https://brand.gupy.io/">Portal</a>
                      <a href="https://www.linkedin.com/company/brand">LinkedIn</a>
                      <a href="https://www.facebook.com/brand">Facebook</a>
                      <a href="https://www.instagram.com/brand">Instagram</a>
                    </body></html>
                    """)));

            var jobs = provider.extract();

            assertEquals(1, jobs.size());
            assertNull(jobs.get(0).metadata().get("companyWebsite"),
                    "portal and social/tracker hosts must never be stored as companyWebsite");
        }

        @Test
        @DisplayName("extract should not set companyWebsite when the detail page returns 404")
        void extract_whenDetailPageReturns404_shouldNotSetCompanyWebsite() {
            var provider = detailProvider(100);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [
                          {"name": "Dev Java", "jobUrl": "%s/gupy/ghost/jobs/1", "publishedDate": "2026-07-01"}
                        ]}
                        """.formatted(baseUrl))));
            stubFor(get(urlEqualTo("/gupy/ghost/jobs/1"))
                    .willReturn(aResponse().withStatus(404)));

            var jobs = provider.extract();

            assertEquals(1, jobs.size());
            assertNull(jobs.get(0).metadata().get("companyWebsite"),
                    "a failed detail fetch must leave the job unchanged — a host failure never fails the provider");
        }

        @Test
        @DisplayName("extract should fetch the detail page exactly once for N jobs sharing one host")
        void extract_whenManyJobsShareOneHost_shouldFetchDetailExactlyOnce() {
            var provider = detailProvider(100);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [
                          {"name": "Dev 1", "jobUrl": "%s/gupy/techco/jobs/1", "publishedDate": "2026-07-01"},
                          {"name": "Dev 2", "jobUrl": "%s/gupy/techco/jobs/2", "publishedDate": "2026-07-01"},
                          {"name": "Dev 3", "jobUrl": "%s/gupy/techco/jobs/3", "publishedDate": "2026-07-01"}
                        ]}
                        """.formatted(baseUrl, baseUrl, baseUrl))));
            // One detail fetch must serve every job of the same host — whichever job URL is
            // the host's "first" (HashMap order is not deterministic → all three stubbed).
            var detailPage = """
                    <html><body><a href="https://www.techco.com.br/">Site</a></body></html>
                    """;
            stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok(detailPage)));
            stubFor(get(urlEqualTo("/gupy/techco/jobs/2")).willReturn(ok(detailPage)));
            stubFor(get(urlEqualTo("/gupy/techco/jobs/3")).willReturn(ok(detailPage)));

            var jobs = provider.extract();

            assertEquals(3, jobs.size());
            verify(1, getRequestedFor(urlMatching("/gupy/techco/jobs/[123]")));
            for (var job : jobs) {
                assertEquals("https://www.techco.com.br", job.metadata().get("companyWebsite"));
            }
        }

        @Test
        @DisplayName("extract should resolve only up to the max-detail-domains cap and skip overflow hosts")
        void extract_whenHostsExceedCap_shouldOnlyResolveUpToCap() {
            var provider = detailProvider(1);
            var port = baseUrl.substring(baseUrl.lastIndexOf(':') + 1);

            stubFor(get(urlPathEqualTo("/api/v1/jobs"))
                    .willReturn(okJson("""
                        {"data": [
                          {"name": "Dev Local", "jobUrl": "%s/gupy/techco/jobs/1", "publishedDate": "2026-07-01"},
                          {"name": "Dev Loop", "jobUrl": "http://127.0.0.1:%s/gupy/dotnet/jobs/2", "publishedDate": "2026-07-01"}
                        ]}
                        """.formatted(baseUrl, port))));
            stubFor(get(urlEqualTo("/gupy/techco/jobs/1")).willReturn(ok("""
                    <html><body><a href="https://www.techco.com.br/">Site</a></body></html>
                    """)));
            stubFor(get(urlEqualTo("/gupy/dotnet/jobs/2")).willReturn(ok("""
                    <html><body><a href="https://www.dotnet.com.br/">Site</a></body></html>
                    """)));

            var jobs = provider.extract();

            // Exactly one host is resolved (whichever is first); the overflow host is
            // skipped without being fetched.
            var withWebsite = jobs.stream()
                    .filter(job -> job.metadata().get("companyWebsite") != null)
                    .toList();
            assertEquals(1, withWebsite.size(), "only one host may be resolved under the cap");
            verify(1, getRequestedFor(urlMatching("/gupy/(techco|dotnet)/jobs/[12]")));
        }
    }
}
