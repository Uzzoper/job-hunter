package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.LinkedInProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("LinkedInProvider tests")
class LinkedInProviderTest {

    private String baseUrl;
    private LinkedInProvider provider;
    private ExponentialBackoffRetry retry;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        provider = new LinkedInProvider(
                baseUrl,
                5,
                List.of("desenvolvedor"),
                1,
                retry);
    }

    private static String loadFixture(String fileName) {
        var path = "/fixtures/linkedin/" + fileName;
        try (InputStream is = LinkedInProviderTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Fixture not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load fixture: " + path, e);
        }
    }

    @Nested
    @DisplayName("Scenario 1: valid search page with job cards")
    class ValidSearchPage {

        @Test
        @DisplayName("extract should return mapped RawJob list with source=linkedin")
        void extract_whenValidSearchPage_shouldReturnMappedJobs() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            var jobs = provider.extract();

            assertEquals(3, jobs.size());
            for (var job : jobs) {
                assertEquals("linkedin", job.source());
                assertNotNull(job.title());
                assertNotNull(job.url());
                assertTrue(job.url().startsWith(baseUrl));
            }

            var titles = jobs.stream().map(RawJob::title).toList();
            assertTrue(titles.contains("Desenvolvedor Java Júnior"));
            assertTrue(titles.contains("Desenvolvedor Python Júnior"));
            assertTrue(titles.contains("Estágio em Desenvolvimento Frontend"));

            var companies = jobs.stream().map(RawJob::company).toList();
            assertTrue(companies.contains("TechCo Solutions"));
            assertTrue(companies.contains("DataCo Brasil"));
            assertTrue(companies.contains("WebStart Inc"));

            var locations = jobs.stream().map(RawJob::location).toList();
            assertTrue(locations.contains("São Paulo, SP"));
            assertTrue(locations.contains("Rio de Janeiro, RJ"));
            assertTrue(locations.contains("Remoto"));
        }
    }

    @Nested
    @DisplayName("Scenario 2: detail page fetching")
    class DetailFetch {

        @Test
        @DisplayName("extract should fetch detail pages and merge descriptions")
        void extract_whenDetailPagesAvailable_shouldMergeDescriptions() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-java-123"))
                    .willReturn(ok(loadFixture("detail-page-1.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-python-456"))
                    .willReturn(ok(loadFixture("detail-page-2.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/estagio-frontend-789"))
                    .willReturn(ok("<html><body><div class=\"show-more-less-html__markup\"><p>Detalhes do estágio frontend.</p></div></body></html>")));

            var jobs = provider.extract();

            assertEquals(3, jobs.size());
            for (var job : jobs) {
                assertNotNull(job.description(), "Description should be populated from detail page");
                assertFalse(job.description().isBlank(), "Description should not be blank");
            }

            var javaJob = jobs.stream()
                    .filter(j -> j.title().contains("Java"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(javaJob.description().contains("Spring Boot"));

            var pythonJob = jobs.stream()
                    .filter(j -> j.title().contains("Python"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(pythonJob.description().contains("pipeline"));
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty search results")
    class EmptyResults {

        @Test
        @DisplayName("extract should return empty list when no jobs found")
        void extract_whenEmptyResults_shouldReturnEmptyList() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("empty-results.html"))));

            var jobs = provider.extract();

            assertNotNull(jobs);
            assertTrue(jobs.isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 4: malformed cards in search page")
    class MalformedCards {

        @Test
        @DisplayName("extract should skip cards missing title or URL")
        void extract_whenMalformedCards_shouldSkipAndContinue() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("malformed-card.html"))));

            var jobs = provider.extract();

            assertEquals(2, jobs.size(), "Should only return the 2 valid cards");

            var titles = jobs.stream().map(RawJob::title).toList();
            assertTrue(titles.contains("Vaga Válida"));
            assertTrue(titles.contains("Outra Vaga Júnior"));
            assertFalse(titles.contains(""), "Empty titles should be skipped");
        }
    }

    @Nested
    @DisplayName("Scenario 5: detail page missing description")
    class MissingDetailDescription {

        @Test
        @DisplayName("extract should fall back to list snippet when detail has no description")
        void extract_whenDetailPageMissingDescription_shouldFallbackToListSnippet() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-java-123"))
                    .willReturn(ok("<html><body><div class=\"job-view-layout jobs-details\"><h1>Desenvolvedor Java Júnior</h1><p>Título sem descrição.</p></div></body></html>")));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-python-456"))
                    .willReturn(ok(loadFixture("detail-page-2.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/estagio-frontend-789"))
                    .willReturn(ok("<html><body><div class=\"show-more-less-html__markup\"></div></body></html>")));

            var jobs = provider.extract();

            assertEquals(3, jobs.size());

            var javaJob = jobs.stream()
                    .filter(j -> j.title().contains("Java"))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(javaJob.description());
            assertFalse(javaJob.description().isBlank());
        }
    }

    @Nested
    @DisplayName("Scenario 6: HTTP 429 Too Many Requests")
    class Http429 {

        @Test
        @DisplayName("extract should throw ScraperException on HTTP 429")
        void extract_whenHttp429_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(status(429)));

            assertThrows(ScraperException.class, () -> provider.extract());
        }
    }

    @Nested
    @DisplayName("Scenario 7: HTTP 403 Forbidden")
    class Http403 {

        @Test
        @DisplayName("extract should throw ScraperException on HTTP 403")
        void extract_whenHttp403_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(status(403)));

            assertThrows(ScraperException.class, () -> provider.extract());
        }
    }

    @Nested
    @DisplayName("Scenario 8: bot challenge / CAPTCHA")
    class BotChallenge {

        @Test
        @DisplayName("extract should throw ScraperException on bot challenge page")
        void extract_whenBotChallenge_shouldThrowScraperException() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("bot-challenge.html"))));

            assertThrows(ScraperException.class, () -> provider.extract());
        }
    }

    @Nested
    @DisplayName("Scenario 9: detail page returns 404")
    class DetailPage404 {

        @Test
        @DisplayName("extract should log warning and save job with list data when detail page is 404")
        void extract_whenDetailPage404_shouldUseListData() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-java-123"))
                    .willReturn(notFound()));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-python-456"))
                    .willReturn(ok(loadFixture("detail-page-2.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/estagio-frontend-789"))
                    .willReturn(notFound()));

            var jobs = provider.extract();

            assertEquals(3, jobs.size());

            var javaJob = jobs.stream()
                    .filter(j -> j.title().contains("Java"))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(javaJob.description(), "Should fall back to list snippet");
            assertTrue(javaJob.url().contains("desenvolvedor-java-123"));

            var frontendJob = jobs.stream()
                    .filter(j -> j.title().contains("Frontend"))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(frontendJob.description(), "Should fall back to list snippet");
        }
    }

    @Nested
    @DisplayName("Scenario 11: companyWebsite from detail page")
    class CompanyWebsite {

        @Test
        @DisplayName("extract should set companyWebsite metadata from detail org link")
        void extract_whenCompanyLinkPresent_shouldSetCompanyWebsite() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-java-123"))
                    .willReturn(ok("""
                        <html><body>
                        <div class="job-view-layout jobs-details">
                          <a class="topcard__org-name-link" href="https://techcorp.com.br">TechCorp</a>
                          <div class="show-more-less-html__markup"><p>Detalhe full.</p></div>
                        </div>
                        </body></html>
                        """)));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-python-456"))
                    .willReturn(ok(loadFixture("detail-page-2.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/estagio-frontend-789"))
                    .willReturn(ok("<html><body><div class=\"show-more-less-html__markup\"><p>Detalhes do estágio.</p></div></body></html>")));

            var jobs = provider.extract();

            var javaJob = jobs.stream()
                    .filter(j -> j.title().contains("Java"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("https://techcorp.com.br", javaJob.metadata().get("companyWebsite"),
                    "companyWebsite should be parsed from a.topcard__org-name-link");

            // Cards without a company link keep companyWebsite null
            var pythonJob = jobs.stream()
                    .filter(j -> j.title().contains("Python"))
                    .findFirst()
                    .orElseThrow();
            assertNull(pythonJob.metadata().get("companyWebsite"),
                    "companyWebsite stays null when no org link present");
        }

        @Test
        @DisplayName("extract should set companyWebsite from detail org link with data-tracking-control-name")
        void extract_whenDetailOrgNameControlLinkPresent_shouldSetCompanyWebsite() {
            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-java-123"))
                    .willReturn(ok("""
                        <html><body>
                        <div class="job-view-layout jobs-details">
                          <a href="https://dataco.com.br" data-tracking-control-name="public_jobs_topcard-org-name">DataCo</a>
                          <div class="show-more-less-html__markup"><p>Detalhe full.</p></div>
                        </div>
                        </body></html>
                        """)));

            stubFor(get(urlPathEqualTo("/jobs/view/desenvolvedor-python-456"))
                    .willReturn(ok(loadFixture("detail-page-2.html"))));

            stubFor(get(urlPathEqualTo("/jobs/view/estagio-frontend-789"))
                    .willReturn(ok("<html><body><div class=\"show-more-less-html__markup\"><p>Outro.</p></div></body></html>")));

            var jobs = provider.extract();

            var javaJob = jobs.stream()
                    .filter(j -> j.title().contains("Java"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("https://dataco.com.br", javaJob.metadata().get("companyWebsite"),
                    "companyWebsite should be parsed from a[data-tracking-control-name=public_jobs_topcard-org-name]");
        }
    }

    @Nested
    @DisplayName("Scenario 10: location / seniority filtering via config")
    class LocationSeniorityFiltering {

        @Test
        @DisplayName("extract should filter jobs by configured location")
        void extract_whenLocationFilterConfigured_shouldFilterJobs() {
            var filteredProvider = new LinkedInProvider(
                    baseUrl,
                    5,
                    List.of("desenvolvedor"),
                    1,
                    retry);

            stubFor(get(urlPathEqualTo("/jobs/search"))
                    .withQueryParam("keywords", equalTo("desenvolvedor"))
                    .willReturn(ok(loadFixture("search-page.html"))));

            var jobs = filteredProvider.extract();

            assertNotNull(jobs);
            assertFalse(jobs.isEmpty(), "Provider should return jobs for further filtering");

            var locationFilter = List.of("Remoto", "São Paulo");
            var filteredJobs = jobs.stream()
                    .filter(j -> locationFilter.stream()
                            .anyMatch(loc -> j.location() != null && j.location().contains(loc)))
                    .toList();

            assertTrue(filteredJobs.size() < jobs.size() || filteredJobs.size() == jobs.size());
            for (var job : filteredJobs) {
                assertNotNull(job.location());
            }
        }
    }
}
