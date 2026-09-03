package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.InfoJobsProvider;
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

@ExtendWith(WireMockExtension.class)
@DisplayName("InfoJobsProvider tests")
class InfoJobsProviderTest {

    private String baseUrl;
    private InfoJobsProvider provider;
    private ExponentialBackoffRetry retry;
    private com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter noopRateLimiter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        noopRateLimiter = new com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter(1000, 100, null);
        var sharedRestClient = org.springframework.web.client.RestClient.builder().baseUrl(baseUrl).build();
        provider = new InfoJobsProvider(baseUrl, 5, List.of("desenvolvedor"), 1, retry,
                sharedRestClient, noopRateLimiter);
    }

    @Nested
    @DisplayName("Scenario 1: valid HTML with job cards")
    class ValidHtml {

        @Test
        @DisplayName("extract should return mapped RawJob list from cards")
        void extract_whenValidHtmlWithCards_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-123__DEV" title="Desenvolvedor Java">Desenvolvedor Java</a></h2>
                          <span class="company">TechCo</span>
                          <span class="localizacao">São Paulo, SP</span>
                          <span class="date">Há 3 dias</span>
                          <p class="descricao">Vaga para desenvolvedor Java</p>
                        </article>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-456__PYTHON" title="Desenvolvedor Python">Desenvolvedor Python</a></h2>
                          <span class="company">DataCo</span>
                          <span class="localizacao">Rio de Janeiro, RJ</span>
                          <span class="date">Há 5 dias</span>
                          <p class="descricao">Vaga para desenvolvedor Python</p>
                        </article>
                        </body></html>
                        """)));

            var jobs = provider.extract();
            assertEquals(2, jobs.size());

            var jobUrls = jobs.stream().map(j -> j.url()).toList();
            assertTrue(jobUrls.stream().anyMatch(u -> u.contains("vaga-de-123")),
                    "Expected job with URL containing 'vaga-de-123' but got: " + jobUrls);
        }
    }

    @Nested
    @DisplayName("Scenario 2: fallback parser")
    class FallbackParser {

        @Test
        @DisplayName("extract should use link-based fallback when no cards found")
        void extract_whenNoCards_shouldUseLinkFallback() {
            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <div class="job-card-list">
                          <div class="job-container">
                            <span class="posted-date">Há 2 dias</span>
                            <a href="/vaga-de-123__DEV" title="Dev Java">Desenvolvedor Java Sênior</a>
                            <span class="company">TechCo</span>
                            <span class="location">São Paulo, SP</span>
                          </div>
                          <div class="job-container">
                            <span class="posted-date">Há 5 dias</span>
                            <a href="/vaga-de-456__PYTHON" title="Dev Python">Desenvolvedor Python Pleno</a>
                            <span class="company">DataCo</span>
                            <span class="location">Rio de Janeiro, RJ</span>
                          </div>
                        </div>
                        </body></html>
                        """)));

            var jobs = provider.extract();
            assertFalse(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 3: bot challenge")
    class BotChallenge {

        @Test
        @DisplayName("extract should handle bot challenge page gracefully")
        void extract_whenBotChallenge_shouldNotThrow() {
            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("<html><body>captcha</body></html>")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 4: empty response")
    class EmptyResponse {

        @Test
        @DisplayName("extract should return empty list for empty HTML")
        void extract_whenEmptyHtml_shouldReturnEmpty() {
            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("<html><body></body></html>")));

            var jobs = provider.extract();
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 19: detail enrichment cap")
    class DetailEnrichmentCap {

        @Test
        @DisplayName("enrich when jobs exceed cap should enrich only first N and mark rest detailSkipped (not failed)")
        void enrich_whenJobsExceedCap_shouldEnrichOnlyFirstNAndMarkRestDetailSkipped() {
            var capProvider = buildCappedProvider(2);

            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-1__DEV" title="Vaga 1">Vaga 1</a></h2>
                          <span class="company">A</span>
                          <p class="descricao">card1</p>
                        </article>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-2__DEV" title="Vaga 2">Vaga 2</a></h2>
                          <span class="company">B</span>
                          <p class="descricao">card2</p>
                        </article>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-3__DEV" title="Vaga 3">Vaga 3</a></h2>
                          <span class="company">C</span>
                          <p class="descricao">card3</p>
                        </article>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-4__DEV" title="Vaga 4">Vaga 4</a></h2>
                          <span class="company">D</span>
                          <p class="descricao">card4</p>
                        </article>
                        </body></html>
                        """)));

            // Stub detail pages for ALL jobs so the test verifies the cap regardless of
            // collection ordering — if all four had detail available, success means only
            // N were fetched (the rest were never fetched and thus marked detailFailed).
            for (int i = 1; i <= 4; i++) {
                stubFor(get(urlPathEqualTo("/vaga-de-" + i + "__DEV"))
                        .willReturn(ok("<html><body><div data-testid=\"job-description\"><p>detail" + i + "</p></div></body></html>")));
            }

            var jobs = capProvider.extract();
            assertEquals(4, jobs.size(), "All 4 jobs should be returned");

            // Exactly maxDetailFetch (2) jobs should be enriched with detail descriptions
            var enriched = jobs.stream()
                    .filter(j -> j.description().startsWith("detail"))
                    .toList();
            assertEquals(2, enriched.size(),
                    "Exactly 2 jobs should be enriched with detail, got descriptions: "
                            + jobs.stream().map(RawJob::description).toList());

            // The remaining 2 jobs keep card snippet and have detailSkipped=true (NOT detailFailed)
            var skipped = jobs.stream()
                    .filter(j -> "true".equals(j.metadata().get("detailSkipped")))
                    .toList();
            assertEquals(2, skipped.size(), "Exactly 2 jobs should have detailSkipped=true");
            for (var job : skipped) {
                assertTrue(job.description().startsWith("card"),
                        "Skipped jobs should keep card snippet, got: " + job.description());
                assertTrue(!job.metadata().containsKey("detailFailed"),
                        "Untried (capped) jobs must NOT be flagged detailFailed");
            }
        }

        private InfoJobsProvider buildCappedProvider(int maxDetailFetch) {
            var permissiveRateLimiter = new com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter(1000, 100, null);
            var sharedRestClient = org.springframework.web.client.RestClient.builder().baseUrl(baseUrl).build();
            return new InfoJobsProvider(
                    baseUrl, 5, List.of("desenvolvedor"), 1, retry,
                    2, 5, sharedRestClient, permissiveRateLimiter, maxDetailFetch);
        }
    }

    @Nested
    @DisplayName("Scenario 8: InfoJobs fetches detail page concurrently")
    class DetailFetch {

        @Test
        @DisplayName("extract should use full description from detail page")
        void extract_whenDetailAvailable_shouldUseFullDescription() {
            var detailProvider = buildDetailProvider();

            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-123__DEV" title="Desenvolvedor Java">Desenvolvedor Java</a></h2>
                          <span class="company">TechCo</span>
                          <span class="localizacao">São Paulo, SP</span>
                          <span class="date">Há 3 dias</span>
                          <p class="descricao">Breve resumo na busca</p>
                        </article>
                        </body></html>
                        """)));

            stubFor(get(urlPathEqualTo("/vaga-de-123__DEV"))
                    .willReturn(ok("""
                        <html><body>
                        <div data-testid="job-description">
                          <p>Responsabilidades: desenvolver APIs REST com Java 21 e Spring Boot.</p>
                          <p>Você atuará no time de plataforma, participando de todas as fases do ciclo de vida.</p>
                          <p>Requisitos: experiência com microsserviços e banco de dados relacional.</p>
                        </div>
                        </body></html>
                        """)));

            var jobs = detailProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertTrue(job.description().contains("Responsabilidades"),
                    "Full detail description should be used, got: " + job.description());
            assertTrue(job.description().contains("microsserviços"),
                    "Detail description should include the full detail text, got: " + job.description());
            assertFalse(job.description().contains("Breve resumo na busca"),
                    "Should NOT use the short card snippet when detail is available");
        }

        @Test
        @DisplayName("extract should respect detail concurrency (at most 2 concurrent detail calls)")
        void extract_whenDetailConcurrent_shouldRespectConcurrency() {
            var detailProvider = buildDetailProvider();
            var detailDelayMillis = 200;

            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-1__DEV" title="Vaga 1">Vaga 1</a></h2>
                          <span class="company">A</span>
                          <p class="descricao">s1</p>
                        </article>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-2__DEV" title="Vaga 2">Vaga 2</a></h2>
                          <span class="company">B</span>
                          <p class="descricao">s2</p>
                        </article>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-3__DEV" title="Vaga 3">Vaga 3</a></h2>
                          <span class="company">C</span>
                          <p class="descricao">s3</p>
                        </article>
                        </body></html>
                        """)));

            for (int i = 1; i <= 3; i++) {
                stubFor(get(urlPathEqualTo("/vaga-de-" + i + "__DEV"))
                        .willReturn(ok("<html><body><div data-testid=\"job-description\"><p>detail " + i + "</p></div></body></html>")
                                .withFixedDelay(detailDelayMillis)));
            }

            long start = System.nanoTime();
            var jobs = detailProvider.extract();
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertEquals(3, jobs.size());
            for (var job : jobs) {
                assertTrue(job.description().startsWith("detail"),
                        "Should have used detail description, got: " + job.description());
            }

            // 3 delayed calls with concurrency 2 complete in ~ceil(3/2)*delay = 400ms;
            // sequential would take ~600ms. A generous midpoint proves concurrency.
            long expectedParallel = (long) Math.ceil(3.0 / 2) * detailDelayMillis;
            long sequential = (long) 3 * detailDelayMillis;
            long cutoff = (expectedParallel + sequential) / 2;
            assertTrue(elapsedMillis < cutoff,
                    "Detail fetches should overlap (concurrency 2): elapsed=" + elapsedMillis
                            + "ms cutoff=" + cutoff + "ms");
        }

        @Test
        @DisplayName("extract should fall back to card snippet and mark detailFailed when detail fetch fails")
        void extract_whenDetailFails_shouldFallbackToSnippetAndMarkDetailFailed() {
            var detailProvider = buildDetailProvider();

            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-999__DEV" title="Desenvolvedor Java">Desenvolvedor Java</a></h2>
                          <span class="company">TechCo</span>
                          <span class="localizacao">São Paulo, SP</span>
                          <p class="descricao">Este é o trecho curto exibido na busca.</p>
                        </article>
                        </body></html>
                        """)));

            stubFor(get(urlPathEqualTo("/vaga-de-999__DEV"))
                    .willReturn(status(500)));

            var jobs = detailProvider.extract();
            assertEquals(1, jobs.size(), "Job should not be discarded when detail fails");

            var job = jobs.get(0);
            assertTrue(job.description().contains("trecho curto"),
                    "Should fall back to the card snippet, got: " + job.description());
            assertEquals("true", job.metadata().get("detailFailed"),
                    "detailFailed metadata should be true when detail fetch fails");
        }

        @Test
        @DisplayName("detail fetch should honor the dedicated short detail timeout while search uses the (longer) shared client")
        void extract_whenDetailSlowerThanDetailTimeout_shouldTimeoutDetailButKeepSearch() {
            // Shared search client has no timeout; the detail client is built with a 1s read
            // timeout (scraper.infojobs.detail-timeout-seconds). Search is fast; detail times out.
            var noRetry = new ExponentialBackoffRetry(1, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1));
            var permissiveRateLimiter = new com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter(1000, 100, null);
            var sharedRestClient = org.springframework.web.client.RestClient.builder().baseUrl(baseUrl).build();
            var shortDetailTimeoutProvider = new InfoJobsProvider(
                    baseUrl, 5, List.of("desenvolvedor"), 1, noRetry,
                    2, 1 /* detail-timeout-seconds = 1s */, sharedRestClient, permissiveRateLimiter);

            // Search page: fast (well within both timeouts) → 1 job found
            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-timeout__DEV" title="Desenvolvedor Java">Desenvolvedor Java</a></h2>
                          <span class="company">TechCo</span>
                          <span class="localizacao">São Paulo, SP</span>
                          <p class="descricao">Trecho curto da busca.</p>
                        </article>
                        </body></html>
                        """).withFixedDelay(100)));

            // Detail page: slower than the 1s detail timeout → detail fetch must time out
            stubFor(get(urlPathEqualTo("/vaga-de-timeout__DEV"))
                    .willReturn(ok("<html><body><div data-testid=\"job-description\"><p>Detalhe.</p></div></body></html>")
                            .withFixedDelay(2_000)));

            var jobs = shortDetailTimeoutProvider.extract();

            assertEquals(1, jobs.size(), "Search should still return the job");
            var job = jobs.get(0);
            assertTrue(job.description().contains("Trecho curto da busca"),
                    "Detail timed out → should fall back to card snippet, got: " + job.description());
            assertEquals("true", job.metadata().get("detailFailed"),
                    "Detail timeout should mark detailFailed (detail honors its own short timeout)");
        }

        @Test
        @DisplayName("extract should parse companyWebsite from card or detail HTML")
        void extract_whenCompanyLinkPresent_shouldSetCompanyWebsiteMetadata() {
            var detailProvider = buildDetailProvider();

            stubFor(get(urlPathEqualTo("/vagas-de-emprego-desenvolvedor.aspx"))
                    .willReturn(ok("""
                        <html><body>
                        <article class="js_rowCard">
                          <h2><a href="/vaga-de-777__DEV" title="Desenvolvedor Java">Desenvolvedor Java</a></h2>
                          <span class="company"><a href="https://techcorp.com.br">TechCorp</a></span>
                          <p class="descricao">Breve resumo na busca</p>
                        </article>
                        </body></html>
                        """)));

            stubFor(get(urlPathEqualTo("/vaga-de-777__DEV"))
                    .willReturn(ok("<html><body><div data-testid=\"job-description\"><p>Detalhe completo.</p></div></body></html>")));

            var jobs = detailProvider.extract();
            assertEquals(1, jobs.size());

            var job = jobs.get(0);
            assertEquals("https://techcorp.com.br", job.metadata().get("companyWebsite"),
                    "companyWebsite should be parsed from the card company link");
        }
    }

    private InfoJobsProvider buildDetailProvider() {
        var permissiveRateLimiter = new com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter(1000, 100, null);
        var sharedRestClient = org.springframework.web.client.RestClient.builder().baseUrl(baseUrl).build();
        return new InfoJobsProvider(
                baseUrl, 5, List.of("desenvolvedor"), 1, retry,
                2, 5, sharedRestClient, permissiveRateLimiter);
    }
}
