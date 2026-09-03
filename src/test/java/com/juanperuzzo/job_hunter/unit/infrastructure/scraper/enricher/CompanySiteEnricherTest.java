package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.enricher;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.enricher.CompanySiteEnricher;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WireMockExtension.class)
@DisplayName("CompanySiteEnricher tests")
class CompanySiteEnricherTest {

    private static final List<String> CONTACT_PATHS =
            List.of("/contato", "/contact", "/trabalhe-conosco", "/carreiras");

    private String baseUrl;
    private CompanySiteEnricher enricher;
    private CompanySiteEnricher throttlingEnricher;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        var retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        enricher = buildEnricher(retry, new TokenBucketRateLimiter(1000, 100, null));
        throttlingEnricher = buildEnricher(retry, new TokenBucketRateLimiter(2.0, 1, null));
    }

    @Nested
    @DisplayName("Scenario 12: skip crawl when job already has email")
    class SkipWhenHasEmail {

        @Test
        @DisplayName("enrich should not crawl when the job already has a contactEmail")
        void enrich_whenJobHasEmail_shouldSkipCrawlAndMakeNoRequest() {
            var job = jobWithEmail("rh@company.com", baseUrl);

            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            verify(0, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should skip when there is no companyWebsite")
        void enrich_whenNoWebsite_shouldReturnUnchanged() {
            var job = job(null, null);

            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
        }

        @Test
        @DisplayName("enrich should skip when the companyWebsite is not a valid URL")
        void enrich_whenInvalidWebsiteUrl_shouldSkipEnrichment() {
            var job = job(null, "ht tp://definitely-not-a-url");

            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
        }
    }

    @Nested
    @DisplayName("Skip portal domains (hotfix: avoid crawling job-portal hosts)")
    class SkipPortalDomains {

        @Test
        @DisplayName("enrich should skip a companyWebsite on *.gupy.io without any HTTP call")
        void enrich_whenCompanyWebsiteIsGupyIo_shouldSkipWithoutHttpCall() {
            var job = job(null, "https://empresa.gupy.io/vagas/12345");

            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
            verify(0, getRequestedFor(urlPathEqualTo("/")));
            verify(0, getRequestedFor(urlPathEqualTo("/robots.txt")));
        }

        @Test
        @DisplayName("enrich should skip a companyWebsite on *.infojobs.com.br")
        void enrich_whenCompanyWebsiteIsInfoJobs_shouldSkipWithoutHttpCall() {
            var job = job(null, "https://empresa.infojobs.com.br/vaga/123");

            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
            verify(0, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should skip a companyWebsite on *.gupy.com.br")
        void enrich_whenCompanyWebsiteIsGupyComBr_shouldSkipWithoutHttpCall() {
            var job = job(null, "https://empresa.gupy.com.br/vagas/123");

            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
            verify(0, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should NOT skip a real corporate domain")
        void enrich_whenCompanyWebsiteIsCorporateDomain_shouldProceedWithCrawl() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/"))
                    .willReturn(ok("<html><body><a href=\"mailto:rh@techcorp.com.br\">RH</a></body></html>")));

            var job = job(null, baseUrl);

            var enriched = enricher.enrich(job);

            assertEquals("rh@techcorp.com.br", enriched.contactEmail());
            verify(1, getRequestedFor(urlPathEqualTo("/")));
        }
    }

    @Nested
    @DisplayName("Scenario 13: crawl homepage then contact path")
    class Crawl {

        @Test
        @DisplayName("enrich should crawl the homepage and extract a mailto email")
        void enrich_whenNoEmail_shouldCrawlHomepageAndExtractMailto() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/"))
                    .willReturn(ok("<html><body><a href=\"mailto:contato@techcorp.com.br\">Contato</a> | rh@techcorp.com.br</body></html>")));

            var enriched = enricher.enrich(job(null, baseUrl));

            assertEquals("contato@techcorp.com.br", enriched.contactEmail());
            verify(1, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should fall back to /contato when the homepage has no email")
        void enrich_whenHomepageNoEmail_shouldTryContactPath() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/")).willReturn(ok("<html><body>Sem e-mail aqui</body></html>")));
            stubFor(get(urlPathEqualTo("/contato"))
                    .willReturn(ok("<html><body>Fale com rh [at] techcorp.com.br</body></html>")));

            var enriched = enricher.enrich(job(null, baseUrl));

            assertEquals("rh@techcorp.com.br", enriched.contactEmail());
            verify(1, getRequestedFor(urlPathEqualTo("/")));
            verify(1, getRequestedFor(urlPathEqualTo("/contato")));
        }

        @Test
        @DisplayName("enrich should join contact paths on the origin, not the companyWebsite sub-path")
        void enrich_whenWebsiteHasSubPath_shouldJoinContactPathOnOrigin() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/")).willReturn(ok("<html><body>Sem e-mail aqui</body></html>")));
            stubFor(get(urlPathEqualTo("/contato"))
                    .willReturn(ok("<html><body>rh [at] techcorp.com.br</body></html>")));

            // companyWebsite has a sub-path (/careers) — the /contato fallback must be
            // fetched from the origin (baseUrl), not baseUrl/careers/contato.
            var enriched = enricher.enrich(job(null, baseUrl + "/careers"));

            assertEquals("rh@techcorp.com.br", enriched.contactEmail());
            verify(1, getRequestedFor(urlPathEqualTo("/")));
            verify(1, getRequestedFor(urlPathEqualTo("/contato")));
            verify(0, getRequestedFor(urlPathEqualTo("/careers/contato")));
        }

        @Test
        @DisplayName("enrich should return the job unchanged when no page yields an email")
        void enrich_whenNoEmailAnywhere_shouldReturnUnchanged() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/")).willReturn(ok("<html><body>home</body></html>")));
            stubFor(get(urlPathEqualTo("/contato")).willReturn(ok("<html><body>talk</body></html>")));
            stubFor(get(urlPathEqualTo("/contact")).willReturn(ok("<html><body>talk</body></html>")));

            var job = job(null, baseUrl);
            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
        }
    }

    @Nested
    @DisplayName("Scenario 14: robots.txt and rate limit")
    class ConservativeCrawl {

        @Test
        @DisplayName("enrich should skip a page disallowed by robots.txt")
        void enrich_whenRobotsDisallows_shouldSkipPage() {
            stubFor(get(urlPathEqualTo("/robots.txt"))
                    .willReturn(ok("User-agent: *\nDisallow: /contato\n")));
            stubFor(get(urlPathEqualTo("/")).willReturn(ok("<html><body>no route</body></html>")));
            stubFor(get(urlPathEqualTo("/contato"))
                    .willReturn(ok("<html><body><a href=\"mailto:rh@techcorp.com.br\">RH</a></body></html>")));
            stubFor(get(urlPathEqualTo("/contact")).willReturn(ok("<html><body>no route</body></html>")));

            var job = job(null, baseUrl);
            var enriched = enricher.enrich(job);

            assertNull(enriched.contactEmail());
            verify(0, getRequestedFor(urlPathEqualTo("/contato")));
            verify(1, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should skip everything when robots.txt disallows the root")
        void enrich_whenRobotsDisallowsRoot_shouldSkipHomepage() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(ok("User-agent: *\nDisallow: /\n")));
            stubFor(get(urlPathEqualTo("/"))
                    .willReturn(ok("<html><body><a href=\"mailto:rh@techcorp.com.br\">RH</a></body></html>")));

            var job = job(null, baseUrl);
            var enriched = enricher.enrich(job);

            assertNull(enriched.contactEmail());
            verify(0, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should throttle requests per domain via the rate limiter")
        void enrich_whenRateLimited_shouldThrottlePerDomain() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/")).willReturn(ok("<html><body>sem e-mail</body></html>")));
            stubFor(get(urlPathEqualTo("/contato")).willReturn(ok("<html><body>sem e-mail</body></html>")));

            var job = job(null, baseUrl);
            long start = System.nanoTime();
            var enriched = throttlingEnricher.enrich(job);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertNull(enriched.contactEmail());
            // robots.txt + homepage + /contato = 3 acquires at 2 permits/s with burst 1
            // → roughly 1s of enforced throttling. Assert it actually blocked.
            assertTrue(elapsedMillis >= 900,
                    "Per-domain rate limit should throttle requests, elapsed=" + elapsedMillis + "ms");
        }
    }

    @Nested
    @DisplayName("Scenario 15: in-memory cache per domain")
    class Cache {

        @Test
        @DisplayName("enrich should hit the cache on the second job for the same domain")
        void enrich_whenSameDomainTwice_shouldHitCacheOnSecondCall() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/"))
                    .willReturn(ok("<html><body><a href=\"mailto:contato@techcorp.com.br\">C</a></body></html>")));

            var first = enricher.enrich(job(null, baseUrl));
            var second = enricher.enrich(job(null, baseUrl + "/"));

            assertEquals("contato@techcorp.com.br", first.contactEmail());
            assertEquals("contato@techcorp.com.br", second.contactEmail());
            verify(1, getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        @DisplayName("enrich should cache a negative result too")
        void enrich_whenNoEmail_shouldCacheNegativeResult() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/")).willReturn(ok("<html><body>sem e-mail</body></html>")));

            var first = enricher.enrich(job(null, baseUrl));
            var second = enricher.enrich(job(null, baseUrl + "/"));

            assertNull(first.contactEmail());
            assertNull(second.contactEmail());
            verify(1, getRequestedFor(urlPathEqualTo("/")));
        }
    }

    @Nested
    @DisplayName("Scenario 16: failure is non-fatal")
    class Failures {

        @Test
        @DisplayName("enrich should return the job unchanged and never throw when the crawl fails")
        void enrich_whenCrawlFails_shouldReturnUnchangedAndNotThrow() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(404)));
            stubFor(get(urlPathEqualTo("/")).willReturn(status(500)));
            stubFor(get(urlPathEqualTo("/contato")).willReturn(status(500)));

            var job = job(null, baseUrl);
            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
        }

        @Test
        @DisplayName("enrich should never throw when the site returns a bot-challenge 403")
        void enrich_whenForbidden_shouldReturnUnchanged() {
            stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(status(403)));
            stubFor(get(urlPathEqualTo("/")).willReturn(status(403)));

            var job = job(null, baseUrl);
            var enriched = enricher.enrich(job);

            assertSame(job, enriched);
            assertNull(enriched.contactEmail());
        }
    }

    private Job job(String contactEmail, String companyWebsite) {
        return new Job(null, "Desenvolvedor Junior", "TechCorp", "https://jobs.example.com/1",
                "descricao", LocalDate.now(), "gupy", contactEmail, companyWebsite);
    }

    private Job jobWithEmail(String contactEmail, String companyWebsite) {
        return job(contactEmail, companyWebsite);
    }

    private static CompanySiteEnricher buildEnricher(ExponentialBackoffRetry retry, RateLimiter rateLimiter) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        var client = RestClient.builder().requestFactory(factory).build();
        return new CompanySiteEnricher(
                client, retry, rateLimiter, true, 2, 2, Duration.ofHours(24), CONTACT_PATHS);
    }
}