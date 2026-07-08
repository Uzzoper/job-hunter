package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
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

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        var retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        provider = new InfoJobsProvider(baseUrl, 5, List.of("desenvolvedor"), 1, retry);
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
}
