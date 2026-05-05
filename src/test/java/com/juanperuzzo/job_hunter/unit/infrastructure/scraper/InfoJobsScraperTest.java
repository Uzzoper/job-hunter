package com.juanperuzzo.job_hunter.unit.infrastructure.scraper;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.InfoJobsScraper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("InfoJobsScraper tests")
class InfoJobsScraperTest {

    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
    }

    @Nested
    @DisplayName("Scenario 1: scraper disabled")
    class DisabledTests {

        @Test
        @DisplayName("fetch should return empty list and make no HTTP request when disabled")
        void fetch_whenDisabled_shouldReturnEmptyListWithoutRequest() {
            var scraper = newScraper(false, List.of("desenvolvedor junior"), List.of(), List.of(), 1, 30, 5, 0);

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
            verify(0, getRequestedFor(anyUrl()));
        }
    }

    @Nested
    @DisplayName("Scenario 2: valid search page with jobs")
    class ValidSearchPageTests {

        @Test
        @DisplayName("fetch should map valid InfoJobs cards to Job objects")
        void fetch_whenValidSearchPage_shouldReturnMappedJobs() {
            var scraper = newScraper(true, List.of("desenvolvedor junior"), List.of(), List.of("Todo Brasil"), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-java-junior__123.aspx">Desenvolvedor Java Júnior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">Todo Brasil</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Desenvolvimento de APIs Java e manutenção de sistemas.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertEquals(1, jobs.size());
            var job = jobs.get(0);
            assertEquals("Desenvolvedor Java Júnior", job.title());
            assertEquals("ACME Tecnologia", job.company());
            assertEquals(baseUrl + "/vaga-de-desenvolvedor-java-junior__123.aspx", job.url());
            assertEquals("Desenvolvimento de APIs Java e manutenção de sistemas.", job.description());
            assertEquals(LocalDate.now(), job.postedAt());
            assertTrue(job.matchScore().isEmpty());
        }

        @Test
        @DisplayName("fetch should map jobs from public InfoJobs-like link markup")
        void fetch_whenPageUsesPublicLinkMarkup_shouldReturnMappedJobs() {
            var scraper = newScraper(true, List.of("desenvolvedor junior"), List.of(), List.of("Todo Brasil"), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml("""
                    <html>
                      <body>
                        <div class="result">
                          <a href="/vaga-de-desenvolvedor-junior-net-csharp__456.aspx">Desenvolvedor(a) Júnior (.NET / C#)</a>
                          <span>28 abr</span>
                          <a href="/empresa-serhum-rh">Serhum RH</a>
                          <span>Todo Brasil</span>
                          <span>R$ 3.655,00</span>
                          <span>Entre 1 e 3 anos</span>
                          <span>Ensino Superior</span>
                          <span>Home office</span>
                          <p>Desenvolvedor(a) Júnior 100% Remoto | CLT Regime: CLT.</p>
                        </div>
                      </body>
                    </html>
                    """)));

            var jobs = scraper.fetch();

            assertEquals(1, jobs.size());
            assertEquals("Desenvolvedor(a) Júnior (.NET / C#)", jobs.get(0).title());
            assertEquals("Serhum RH", jobs.get(0).company());
            assertEquals(baseUrl + "/vaga-de-desenvolvedor-junior-net-csharp__456.aspx", jobs.get(0).url());
        }
    }

    @Nested
    @DisplayName("Keyword filtering")
    class KeywordFilteringTests {

        @Test
        @DisplayName("fetch should discard jobs whose title does not match configured keywords")
        void fetch_whenTitleDoesNotMatchKeywords_shouldDiscardJob() {
            var scraper = newScraper(true, List.of("desenvolvedor junior"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-analista-suporte-tecnico__123.aspx">Analista de Suporte Técnico</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">Todo Brasil</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Atendimento e suporte técnico.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
        }

        @Test
        @DisplayName("fetch should request InfoJobs multi-word keywords using plus slug encoding")
        void fetch_whenKeywordHasMultipleWords_shouldUseInfoJobsPlusSlugEncoding() {
            var scraper = newScraper(true, List.of("desenvolvedor junior"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards(""))));

            scraper.fetch();

            verify(getRequestedFor(urlEqualTo("/vagas-de-emprego-desenvolvedor%2Bjunior.aspx")));
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty search page")
    class EmptySearchPageTests {

        @Test
        @DisplayName("fetch should return empty list when page contains no job cards")
        void fetch_whenEmptySearchPage_shouldReturnEmptyList() {
            var scraper = newScraper(true, List.of("desenvolvedor junior"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards(""))));

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 4: duplicate jobs")
    class DuplicateJobsTests {

        @Test
        @DisplayName("fetch should deduplicate jobs by URL across keywords")
        void fetch_whenDuplicateJobsAcrossKeywords_shouldReturnOneJobByUrl() {
            var scraper = newScraper(true, List.of("desenvolvedor junior", "java junior"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-java-junior__123.aspx">Desenvolvedor Java Júnior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">Todo Brasil</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Desenvolvimento backend.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertEquals(1, jobs.size());
        }
    }

    @Nested
    @DisplayName("Scenario 5: excluded seniority")
    class ExcludedSeniorityTests {

        @Test
        @DisplayName("fetch should discard senior and pleno jobs")
        void fetch_whenExcludedSeniority_shouldDiscardJob() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of("senior", "sênior", "pleno"), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-java-senior__123.aspx">Desenvolvedor Java Senior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">Todo Brasil</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Desenvolvimento backend.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 6: non-developer business roles")
    class BusinessRoleTests {

        @Test
        @DisplayName("fetch should discard business development false positives")
        void fetch_whenBusinessDevelopmentRole_shouldDiscardJob() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of("bdr", "desenvolvedor de negócios"), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-bdr-desenvolvedor-de-negocios__123.aspx">BDR - Desenvolvedor de Negócios Júnior</a>
                      <span data-testid="company-name">ACME Vendas</span>
                      <span data-testid="job-location">Ponta Grossa - PR</span>
                      <span data-testid="work-model">Presencial</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Prospecção comercial.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 7: location filtering")
    class LocationFilteringTests {

        @Test
        @DisplayName("fetch should discard non-remote jobs outside configured locations")
        void fetch_whenOutsideConfiguredLocation_shouldDiscardJob() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of("Ponta Grossa", "PR"), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-python-junior__123.aspx">Desenvolvedor Python Júnior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">São Paulo - SP</span>
                      <span data-testid="work-model">Presencial</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Desenvolvimento Python.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 8: recent remote job")
    class RemoteJobTests {

        @Test
        @DisplayName("fetch should accept remote jobs even when location is outside configured cities")
        void fetch_whenRemoteJob_shouldAcceptJob() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of("Ponta Grossa", "PR"), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-backend-junior__123.aspx">Desenvolvedor Backend Júnior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">São Paulo - SP</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">Ontem</span>
                      <p data-testid="job-snippet">Desenvolvimento backend.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertEquals(1, jobs.size());
            assertEquals(LocalDate.now().minusDays(1), jobs.get(0).postedAt());
        }
    }

    @Nested
    @DisplayName("Scenario 9: old job listing")
    class OldJobTests {

        @Test
        @DisplayName("fetch should discard jobs older than max age")
        void fetch_whenOldJob_shouldDiscardJob() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-java-junior__123.aspx">Desenvolvedor Java Júnior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">Todo Brasil</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">1 jan</span>
                      <p data-testid="job-snippet">Desenvolvimento backend.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 10: HTTP error, timeout or bot challenge")
    class ErrorTests {

        @Test
        @DisplayName("fetch should throw ScraperException when endpoint returns HTTP 403")
        void fetch_whenHttp403_shouldThrowScraperException() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(forbidden()));

            var exception = assertThrows(ScraperException.class, scraper::fetch);

            assertTrue(exception.getMessage().contains("403"));
        }

        @Test
        @DisplayName("fetch should throw ScraperException when endpoint returns HTTP 429")
        void fetch_whenHttp429_shouldThrowScraperException() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(aResponse().withStatus(429)));

            var exception = assertThrows(ScraperException.class, scraper::fetch);

            assertTrue(exception.getMessage().contains("429"));
        }

        @Test
        @DisplayName("fetch should throw ScraperException when response contains bot challenge")
        void fetch_whenBotChallenge_shouldThrowScraperException() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml("""
                    <html>
                      <body>
                        <h1>Confirme que você não é um robô</h1>
                        <div class="captcha">captcha</div>
                      </body>
                    </html>
                    """)));

            assertThrows(ScraperException.class, scraper::fetch);
        }

        @Test
        @DisplayName("fetch should throw ScraperException when request times out")
        void fetch_whenTimeout_shouldThrowScraperException() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of(), 1, 30, 1, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("")).withFixedDelay(2_000)));

            var exception = assertThrows(ScraperException.class, scraper::fetch);

            assertTrue(exception.getMessage().toLowerCase().contains("timeout")
                    || exception.getMessage().toLowerCase().contains("timed out"));
        }
    }

    @Nested
    @DisplayName("Scenario 11: malformed or incomplete job card")
    class MalformedCardTests {

        @Test
        @DisplayName("fetch should skip incomplete cards and continue mapping valid ones")
        void fetch_whenCardIsIncomplete_shouldSkipAndContinue() {
            var scraper = newScraper(true, List.of("desenvolvedor"), List.of(), List.of(), 1, 30, 5, 0);
            stubFor(get(anyUrl()).willReturn(okHtml(pageWithCards("""
                    <article data-testid="job-card">
                      <span data-testid="company-name">Missing Title Company</span>
                      <span data-testid="posted-date">Hoje</span>
                    </article>
                    <article data-testid="job-card">
                      <a data-testid="job-title" href="/vaga-de-desenvolvedor-java-junior__123.aspx">Desenvolvedor Java Júnior</a>
                      <span data-testid="company-name">ACME Tecnologia</span>
                      <span data-testid="job-location">Todo Brasil</span>
                      <span data-testid="work-model">Home office</span>
                      <span data-testid="posted-date">Hoje</span>
                      <p data-testid="job-snippet">Desenvolvimento backend.</p>
                    </article>
                    """))));

            var jobs = scraper.fetch();

            assertEquals(1, jobs.size());
            assertEquals("Desenvolvedor Java Júnior", jobs.get(0).title());
        }
    }

    private InfoJobsScraper newScraper(boolean enabled, List<String> keywords, List<String> excludeKeywords,
            List<String> locations, int maxPages, int maxAgeDays, int timeoutSeconds, long delayMillis) {
        return new InfoJobsScraper(baseUrl, enabled, keywords, excludeKeywords, locations, maxPages, maxAgeDays,
                timeoutSeconds, delayMillis);
    }

    private String pageWithCards(String cards) {
        return """
                <html>
                  <body>
                    <main>
                      %s
                    </main>
                  </body>
                </html>
                """.formatted(cards);
    }

    private ResponseDefinitionBuilder okHtml(String html) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(html);
    }
}
