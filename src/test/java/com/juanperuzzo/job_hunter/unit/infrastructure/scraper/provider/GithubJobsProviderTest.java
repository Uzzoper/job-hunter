package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GithubJobsProvider;
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
 * Contract: docs/specs/github-vagas.md. The GitHub Issues REST API returns a
 * top-level JSON array (no envelope); every issue with a {@code pull_request}
 * key is a PR, not a job, and must be skipped. Requests must carry the
 * {@code Accept: application/vnd.github+json} and a {@code User-Agent} header.
 */
@ExtendWith(WireMockExtension.class)
@DisplayName("GithubJobsProvider tests")
class GithubJobsProviderTest {

    private static final String FRONTEND_REPO = "frontendbr/vagas";
    private static final String BACKEND_REPO = "backend-br/vagas";

    private String baseUrl;
    private GithubJobsProvider provider;
    private ExponentialBackoffRetry retry;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
        provider = new GithubJobsProvider(baseUrl, 5, List.of(FRONTEND_REPO), retry);
    }

    private static void stubIssues(String repo, String body) {
        stubFor(get(urlPathEqualTo("/repos/" + repo + "/issues"))
                .withQueryParam("state", equalTo("open"))
                .withQueryParam("per_page", equalTo("100"))
                .willReturn(okJson(body)));
    }

    @Nested
    @DisplayName("Scenario 1: valid repo response")
    class ValidResponse {

        @Test
        @DisplayName("extract should skip PR nodes and blank titles, map the rest with correct headers")
        void extract_whenValidRepo_shouldSkipPrAndBlankAndMapJobs() {
            stubIssues(FRONTEND_REPO, """
                [
                  {
                    "title": "Desenvolvedor Frontend Júnior [Remoto] - Evertec",
                    "html_url": "https://github.com/frontendbr/vagas/issues/1234",
                    "number": 1234,
                    "created_at": "2026-09-01T12:30:00Z",
                    "body": "Vaga para dev frontend. Enviar CV para email@empresa.com",
                    "labels": [{"name": "Remoto"}, {"name": "CLT"}, {"name": "Júnior"}]
                  },
                  {
                    "title": "Update README with new rules",
                    "html_url": "https://github.com/frontendbr/vagas/issues/1235",
                    "number": 1235,
                    "pull_request": {"url": "https://api.github.com/repos/frontendbr/vagas/pulls/1235"}
                  },
                  {
                    "title": "",
                    "html_url": "https://github.com/frontendbr/vagas/issues/1236",
                    "number": 1236
                  },
                  {
                    "title": "Backend Developer na XPTO",
                    "html_url": "https://github.com/frontendbr/vagas/issues/987",
                    "number": 987,
                    "created_at": "2026-08-15T10:00:00Z",
                    "body": "Vaga backend.",
                    "labels": [{"name": "Híbrido"}, {"name": "PJ"}]
                  }
                ]
                """);

            var jobs = provider.extract();
            assertEquals(2, jobs.size());

            var junior = byUrl(jobs, "https://github.com/frontendbr/vagas/issues/1234");
            assertEquals("Desenvolvedor Frontend Júnior [Remoto] - Evertec", junior.title(),
                    "title must be kept verbatim for the normalizer");
            assertEquals("Evertec", junior.company());
            assertEquals("2026-09-01", junior.rawDate());
            assertEquals("Remoto", junior.location());
            assertEquals("Remoto", junior.workModel());
            assertEquals("github", junior.source());
            assertEquals("Remoto,CLT,Júnior", junior.metadata().get("labels"));
            assertEquals("1234", junior.metadata().get("issue"));
            assertTrue(junior.description().contains("email@empresa.com"),
                    "raw body must flow to EmailExtractor downstream, unchanged");

            var backend = byUrl(jobs, "https://github.com/frontendbr/vagas/issues/987");
            assertEquals("XPTO", backend.company());
            assertEquals("Híbrido", backend.workModel());
            assertEquals("Híbrido", backend.location());
            assertEquals("Híbrido,PJ", backend.metadata().get("labels"));
            assertEquals("987", backend.metadata().get("issue"));

            verify(getRequestedFor(urlPathEqualTo("/repos/frontendbr/vagas/issues"))
                    .withHeader("Accept", equalTo("application/vnd.github+json"))
                    .withHeader("User-Agent", equalTo("JobHunter/1.0")));
        }
    }

    @Nested
    @DisplayName("Scenario 2: RFC 5988 Link pagination")
    class Pagination {

        @Test
        @DisplayName("extract should follow Link rel=next while present and collect both pages")
        void extract_whenNextLinkPresent_shouldFollowPagination() {
            var next = "<" + baseUrl + "/repos/frontendbr/vagas/issues?state=open&per_page=100&page=2>; rel=\"next\"";
            var last = "<" + baseUrl + "/repos/frontendbr/vagas/issues?state=open&per_page=100&page=2>; rel=\"last\"";

            stubFor(get(urlPathEqualTo("/repos/frontendbr/vagas/issues"))
                    .withQueryParam("state", equalTo("open"))
                    .withQueryParam("per_page", equalTo("100"))
                    .willReturn(okJson("""
                        [
                          {"title": "Dev página 1", "html_url": "https://github.com/frontendbr/vagas/issues/1",
                           "number": 1, "created_at": "2026-09-01T00:00:00Z", "labels": []}
                        ]
                        """).withHeader("Link", next + ", " + last)));
            stubFor(get(urlPathEqualTo("/repos/frontendbr/vagas/issues"))
                    .withQueryParam("state", equalTo("open"))
                    .withQueryParam("per_page", equalTo("100"))
                    .withQueryParam("page", equalTo("2"))
                    .willReturn(okJson("""
                        [
                          {"title": "Dev página 2", "html_url": "https://github.com/frontendbr/vagas/issues/2",
                           "number": 2, "created_at": "2026-09-02T00:00:00Z", "labels": []}
                        ]
                        """)));

            var jobs = provider.extract();
            assertEquals(2, jobs.size());
            assertNotNull(byUrl(jobs, "https://github.com/frontendbr/vagas/issues/1"));
            assertNotNull(byUrl(jobs, "https://github.com/frontendbr/vagas/issues/2"));

            verify(2, getRequestedFor(urlPathEqualTo("/repos/frontendbr/vagas/issues")));
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty repo")
    class EmptyRepo {

        @Test
        @DisplayName("extract should return empty list when the repo has no open issues")
        void extract_whenEmptyRepo_shouldReturnEmptyList() {
            stubIssues(FRONTEND_REPO, "[]");

            assertTrue(provider.extract().isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 4: 404 repo is skipped")
    class NotFound {

        @Test
        @DisplayName("extract should skip a dead repo and return partial results")
        void extract_when404_shouldSkipRepoAndReturnOtherRepos() {
            stubFor(get(urlPathEqualTo("/repos/frontendbr/vagas/issues"))
                    .withQueryParam("state", equalTo("open"))
                    .withQueryParam("per_page", equalTo("100"))
                    .willReturn(notFound()));
            stubIssues(BACKEND_REPO, """
                [
                  {"title": "Dev Backend", "html_url": "https://github.com/backend-br/vagas/issues/5",
                   "number": 5, "created_at": "2026-09-01T00:00:00Z", "labels": []}
                ]
                """);

            var multiRepoProvider = new GithubJobsProvider(baseUrl, 5, List.of(FRONTEND_REPO, BACKEND_REPO), retry);
            var jobs = multiRepoProvider.extract();

            assertEquals(1, jobs.size());
            assertEquals("Dev Backend", jobs.get(0).title());
        }
    }

    @Nested
    @DisplayName("Scenario 5: 429 retry then skip")
    class TooManyRequests {

        @Test
        @DisplayName("extract should return empty list when the repo keeps answering 429")
        void extract_when429Exhausted_shouldSkipRepo() {
            stubFor(get(urlPathEqualTo("/repos/frontendbr/vagas/issues"))
                    .withQueryParam("state", equalTo("open"))
                    .withQueryParam("per_page", equalTo("100"))
                    .willReturn(status(429)));

            assertTrue(provider.extract().isEmpty());
        }
    }

    @Nested
    @DisplayName("Scenario 6: company parse matrix")
    class CompanyMatrix {

        @Test
        @DisplayName("extract should parse the title suffix after the last separator, null when absent")
        void extract_whenVariousTitleSuffixes_shouldParseCompany() {
            stubIssues(FRONTEND_REPO, """
                [
                  {"title": "Frontend Dev - Evertec",
                   "html_url": "https://github.com/frontendbr/vagas/issues/11", "number": 11,
                   "created_at": "2026-09-01T00:00:00Z", "labels": []},
                  {"title": "Backend Dev na XPTO",
                   "html_url": "https://github.com/frontendbr/vagas/issues/12", "number": 12,
                   "created_at": "2026-09-01T00:00:00Z", "labels": []},
                  {"title": "Mobile Dev @ Nubank",
                   "html_url": "https://github.com/frontendbr/vagas/issues/13", "number": 13,
                   "created_at": "2026-09-01T00:00:00Z", "labels": []},
                  {"title": "Dev Fullstack [São Paulo]",
                   "html_url": "https://github.com/frontendbr/vagas/issues/14", "number": 14,
                   "created_at": "2026-09-01T00:00:00Z", "labels": []}
                ]
                """);

            var jobs = provider.extract();
            assertEquals(4, jobs.size());

            assertEquals("Evertec", byUrl(jobs, "https://github.com/frontendbr/vagas/issues/11").company());
            assertEquals("XPTO", byUrl(jobs, "https://github.com/frontendbr/vagas/issues/12").company());
            assertEquals("Nubank", byUrl(jobs, "https://github.com/frontendbr/vagas/issues/13").company());
            assertNull(byUrl(jobs, "https://github.com/frontendbr/vagas/issues/14").company(),
                    "no suffix separator means no company, not an empty string");
        }
    }

    @Nested
    @DisplayName("Scenario 7: workModel and location matrix")
    class WorkModelMatrix {

        @Test
        @DisplayName("extract should derive workModel from labels first, then title prefix, none when absent")
        void extract_whenVariedSignals_shouldMapWorkModelAndLocation() {
            stubIssues(FRONTEND_REPO, """
                [
                  {"title": "Desenvolvedor Remoto",
                   "html_url": "https://github.com/frontendbr/vagas/issues/21", "number": 21,
                   "created_at": "2026-09-01T00:00:00Z",
                   "labels": [{"name": "Remoto"}]},
                  {"title": "[Híbrido -SP] Dev Júnior",
                   "html_url": "https://github.com/frontendbr/vagas/issues/22", "number": 22,
                   "created_at": "2026-09-01T00:00:00Z",
                   "labels": [{"name": "CLT"}]},
                  {"title": "Desenvolvedor Pleno",
                   "html_url": "https://github.com/frontendbr/vagas/issues/23", "number": 23,
                   "created_at": "2026-09-01T00:00:00Z",
                   "labels": [{"name": "CLT"}]}
                ]
                """);

            var jobs = provider.extract();
            assertEquals(3, jobs.size());

            var byLabel = byUrl(jobs, "https://github.com/frontendbr/vagas/issues/21");
            assertEquals("Remoto", byLabel.workModel());
            assertEquals("Remoto", byLabel.location());

            var byPrefix = byUrl(jobs, "https://github.com/frontendbr/vagas/issues/22");
            assertEquals("Híbrido", byPrefix.workModel(),
                    "labels lack the model, the [Híbrido -SP] prefix must provide it");
            assertEquals("SP", byPrefix.location(),
                    "prefix location fills in when labels carry none");

            var nothing = byUrl(jobs, "https://github.com/frontendbr/vagas/issues/23");
            assertNull(nothing.workModel());
            assertNull(nothing.location());
        }
    }

    /** Dedupe happens into a HashMap — look jobs up by URL instead of position. */
    private static RawJob byUrl(List<RawJob> jobs, String url) {
        return jobs.stream().filter(j -> url.equals(j.url())).findFirst().orElseThrow();
    }
}