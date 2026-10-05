package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyMcpProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.resolver.HttpCompanyDomainResolver;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GupyMcpProvider — gupy-mcp-provider.md §6.
 *
 * <p>Fixtures are recorded from the live MCP endpoint
 * ({@code https://candidates.mcp.api.gupy.io/mcp}, probed 2026-10-04): the real wire
 * form is SSE-framed ({@code event: message} + {@code data: {...}}), the tool result
 * lives in {@code result.content[0].text} as a JSON <em>string</em>, and the payload
 * nests as {@code {"data":{"data":[...],"pagination":{...}}}}.
 */
@ExtendWith(WireMockExtension.class)
@DisplayName("GupyMcpProvider tests")
class GupyMcpProviderTest {

    private static final String MCP_PATH = "/mcp";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Recorded live id=12436826 (ISA SAÚDE, hybrid) — description trimmed for readability. */
    private static final String JOB_ISA = job(
            12436826, 92107, "Desenvolvedor Junior",
            "Incluir a descrição da vaga: A missão do Desenvolvedor Full Stack é desenvolver, manter e evoluir aplicações web.",
            "ISA SAÚDE", "vacancy_type_effective", "2026-09-08T17:57:28.495Z",
            "São Paulo", "São Paulo", "Brasil",
            "https://vagisisasaude.gupy.io/job/eyJqb2JJZCI6MTI0MzY4MjYsInNvdXJjZSI6Im1jcF9jYW5kaWRhdGUifQ==?jobBoardSource=mcp_candidate",
            "hybrid", false, "Esta vaga não possui faixa salarial informada.");

    /** Recorded live id=12378287 (remote, state absent) — description trimmed. */
    private static final String JOB_REMOTE = job(
            12378287, 92108, "Dev Frontend Remoto", "Vaga 100% remota.",
            "Neohype Digital - Innovations", "vacancy_type_talent_pool", "2026-10-01T09:00:00.000Z",
            "Recife", null, "Brasil",
            "https://neohype.gupy.io/job/eyJqb2JJZCI6MTIzNzgyODd9?jobBoardSource=mcp_candidate",
            "remote", true, "Esta vaga não possui faixa salarial informada.");

    private String baseUrl;
    private String mcpUrl;
    private int port;
    private ExponentialBackoffRetry retry;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        port = wmRuntimeInfo.getHttpPort();
        mcpUrl = baseUrl + MCP_PATH;
        retry = new ExponentialBackoffRetry(2, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(2));
    }

    private GupyMcpProvider provider(List<String> keywords, int limit, int maxJobs) {
        return new GupyMcpProvider(mcpUrl, 5, keywords, limit, maxJobs, retry, null, 0);
    }

    private GupyMcpProvider provider(int limit) {
        return provider(List.of("desenvolvedor junior"), limit, 200);
    }

    // ------------------------------------------------------------------ fixtures

    /** Builds a search item with the field set the live endpoint returns. */
    private static String job(
            long id, long companyId, String name, String description, String careerPageName,
            String type, String publishedDate, String city, String state, String country,
            String jobUrl, String workplaceType, boolean disabilities, String salaryLabel) {
        var node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("companyId", companyId);
        node.put("name", name);
        node.put("description", description);
        node.put("careerPageName", careerPageName);
        node.put("type", type);
        node.put("publishedDate", publishedDate);
        node.put("applicationDeadline", "2026-11-30");
        node.put("city", city);
        if (state == null) {
            node.putNull("state");
        } else {
            node.put("state", state);
        }
        node.put("country", country);
        node.put("jobUrl", jobUrl);
        if (workplaceType != null) {
            node.put("workplaceType", workplaceType);
        }
        node.put("disabilities", disabilities);
        node.put("isConfidentialCareerPage", false);
        var salary = node.putObject("salary");
        salary.put("status", "not_disclosed");
        salary.put("label", salaryLabel);
        salary.put("confidence", "high");
        return node.toString();
    }

    /** Unchecked {@code readTree} for fixture tweaks. */
    private static ObjectNode object(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same shape with an explicit id, url and workplaceType — for matrix/edge fixtures. */
    private static String variant(String base, long id, String jobUrl, String workplaceType, String name) {
        var node = object(base);
        node.put("id", id);
        node.put("jobUrl", jobUrl);
        if (workplaceType == null) {
            node.remove("workplaceType");
        } else {
            node.put("workplaceType", workplaceType);
        }
        node.put("name", name);
        return node.toString();
    }

    private static String sse(String json) {
        return "event: message\ndata: " + json + "\n\n";
    }

    /** {@code {"result":{"content":[{"type":"text","text":"<innerJson>"}]}}} — innerJson escaped. */
    private static String envelope(String innerJson) {
        try {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":"
                    + MAPPER.writeValueAsString(innerJson) + "}]}}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String searchResult(String jobsJsonArray, int total, int limit, int offset) {
        var inner = "{\"data\":{\"data\":" + jobsJsonArray
                + ",\"pagination\":{\"total\":" + total + ",\"limit\":" + limit + ",\"offset\":" + offset + "}}}";
        return envelope(inner);
    }

    private static ResponseDefinitionBuilder sseOk(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body);
    }

    private void stubInitialize() {
        var result = "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{\"listChanged\":true}},"
                + "\"serverInfo\":{\"name\":\"gupy-portal-mcp\",\"version\":\"1.0.0\"}}";
        var body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}";
        stubFor(post(urlPathEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("initialize")))
                .willReturn(sseOk(sse(body))));
    }

    private void stubSearch(String term, int offset, String jobsJsonArray, int total, int limit) {
        stubFor(post(urlPathEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.method", equalTo("tools/call")))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("search_jobs")))
                .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo(term)))
                .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo(String.valueOf(offset))))
                .willReturn(sseOk(sse(searchResult(jobsJsonArray, total, limit, offset)))));
    }

    private void stubJobById(long id, String jobJson) {
        stubFor(post(urlPathEqualTo(MCP_PATH))
                .withRequestBody(matchingJsonPath("$.params.name", equalTo("get_job_by_id")))
                .withRequestBody(matchingJsonPath("$.params.arguments.id", equalTo(String.valueOf(id))))
                .willReturn(sseOk(sse(envelope("{\"data\":" + jobJson + "}")))));
    }

    // -------------------------------------------------------------- valid search

    @Nested
    @DisplayName("Scenario 1: valid search response")
    class ValidSearch {

        @Test
        @DisplayName("extract should map every spec field from a recorded live search_jobs result")
        void extract_whenValidSearchResponse_shouldMapAllSpecFields() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals(1, jobs.size());
            var job = jobs.get(0);
            assertEquals("Desenvolvedor Junior", job.title());
            assertEquals("ISA SAÚDE", job.company());
            assertEquals("https://vagisisasaude.gupy.io/job/eyJqb2JJZCI6MTI0MzY4MjYsInNvdXJjZSI6Im1jcF9jYW5kaWRhdGUifQ==?jobBoardSource=mcp_candidate",
                    job.url());
            assertTrue(job.description().startsWith("Incluir a descrição da vaga:"));
            assertEquals("2026-09-08", job.rawDate(), "publishedDate ISO → yyyy-MM-dd");
            assertEquals("São Paulo, São Paulo", job.location(), "city + state, no BR country");
            assertEquals("Híbrido", job.workModel());
            assertEquals("gupy", job.source());
            assertEquals("92107", job.metadata().get("companyId"));
            assertEquals("false", job.metadata().get("disabilities"));
            assertEquals("Esta vaga não possui faixa salarial informada.", job.metadata().get("salary"));
            assertEquals("vacancy_type_effective", job.metadata().get("type"));
        }

        @Test
        @DisplayName("extract should parse both the live SSE framing and a plain JSON envelope")
        void extract_whenEnvelopeIsSseFramedOrPlainJson_shouldParseBoth() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);

            assertEquals(1, provider(100).extract().size());

            // Same payload without SSE framing (the spec §6 example shape).
            stubFor(post(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("desenvolvedor junior")))
                    .willReturn(okJson(searchResult("[" + JOB_REMOTE + "]", 509, 100, 0))));

            var plain = provider(100).extract();

            assertEquals(1, plain.size());
            assertEquals("Dev Frontend Remoto", plain.get(0).title());
        }

        @Test
        @DisplayName("extract should initialize once and search once per keyword")
        void extract_whenFetching_shouldHandshakeOnceAndSearchPerKeyword() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);
            stubSearch("dev junior", 0, "[" + JOB_REMOTE + "]", 509, 100);

            var jobs = provider(List.of("desenvolvedor junior", "dev junior"), 100, 200).extract();

            assertEquals(2, jobs.size());
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.method", equalTo("initialize"))));
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("desenvolvedor junior"))));
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("dev junior"))));
        }

        @Test
        @DisplayName("extract should dedupe the same URL across keywords")
        void extract_whenSameUrlAcrossKeywords_shouldDeduplicate() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);
            stubSearch("dev junior", 0, "[" + JOB_ISA + "]", 509, 100);

            var jobs = provider(List.of("desenvolvedor junior", "dev junior"), 100, 200).extract();

            assertEquals(1, jobs.size());
            assertEquals("Desenvolvedor Junior", jobs.get(0).title());
        }
    }

    // ------------------------------------------------------- description fallback

    @Nested
    @DisplayName("get_job_by_id description fallback")
    class DescriptionFallback {

        @Test
        @DisplayName("extract should call get_job_by_id when a search item lacks description")
        void extract_whenDescriptionMissing_shouldFallbackToGetJobById() {
            var withoutDescription = variant(JOB_ISA, 12436826,
                    "https://vagisisasaude.gupy.io/job/eyJqb2JJZCI6MTI0MzY4MjY=?jobBoardSource=mcp_candidate",
                    "hybrid", "Desenvolvedor Junior");
            var node = object(withoutDescription);
            node.put("description", "");
            withoutDescription = node.toString();

            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + withoutDescription + "]", 509, 100);
            stubJobById(12436826, JOB_ISA);

            var jobs = provider(100).extract();

            assertEquals(1, jobs.size());
            assertTrue(jobs.get(0).description().startsWith("Incluir a descrição da vaga:"),
                    "the detail response must supply the missing description");
        }

        @Test
        @DisplayName("extract should not call get_job_by_id when search already inlined the description")
        void extract_whenDescriptionInlined_shouldNotCallGetJobById() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);

            provider(100).extract();

            verify(0, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.name", equalTo("get_job_by_id"))));
        }

        @Test
        @DisplayName("extract should keep the search item when the get_job_by_id fallback fails")
        void extract_whenFallbackFails_shouldKeepSearchItem() {
            var node = object(JOB_ISA);
            node.put("description", "");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + node + "]", 509, 100);
            stubFor(post(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.name", equalTo("get_job_by_id")))
                    .willReturn(aResponse().withStatus(500)));

            var jobs = provider(100).extract();

            assertEquals(1, jobs.size(), "a failed fallback must not drop the job");
            assertNull(jobs.get(0).description());
        }
    }

    // ------------------------------------------------------------- pagination

    @Nested
    @DisplayName("Offset pagination")
    class Pagination {

        private static final String PAGE_3 = variant(JOB_ISA, 4242,
                "https://terceira.gupy.io/job/eyJqb2JJZCI0MjQyfQ==?jobBoardSource=mcp_candidate",
                "hybrid", "Desenvolvedor Back-end");

        @Test
        @DisplayName("extract should request the next offset while pages are full and merge results")
        void extract_whenFirstPageIsFull_shouldRequestNextOffsetAndMerge() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "," + JOB_REMOTE + "]", 509, 2);
            stubSearch("desenvolvedor junior", 2, "[" + PAGE_3 + "]", 509, 2);

            var jobs = provider(2).extract();

            assertEquals(3, jobs.size());
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo("2"))));
            verify(0, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo("4"))));
        }

        @Test
        @DisplayName("extract should NOT break early when the first page of a subsequent keyword is all duplicates of earlier results")
        void extract_whenFirstPageOfKeywordFullyOverlaps_shouldNotBreakEarly() {
            stubInitialize();
            // First keyword gives job A.
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 1);
            // Second keyword's first page is a full replay of job A; the tail (job B) must still be fetched.
            stubSearch("dev junior", 0, "[" + JOB_ISA + "]", 509, 1);
            var jobB = variant(JOB_REMOTE, 2222,
                    "https://neohype.gupy.io/job/eyJqb2JJZCI6MjIyMjI=?jobBoardSource=mcp_candidate",
                    "remote", "Dev Back-end Remoto");
            stubSearch("dev junior", 1, "[" + jobB + "]", 509, 1);

            var jobs = provider(List.of("desenvolvedor junior", "dev junior"), 1, 200).extract();

            assertEquals(2, jobs.size(), "the tail page must be fetched despite the overlapping head");
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("dev junior")))
                    .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo("0"))));
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("dev junior")))
                    .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo("1"))));
        }


        @Test
        @DisplayName("extract should stop paginating when the next page repeats the same URLs after offset > 0")
        void extract_whenNextPageAtOffsetGreaterThanZeroRepeatsSameUrls_shouldStopPaginating() {
            // Live behaviour with limit=100: offset>0 replays page 1; stop on no progress.
            var replay = "[" + JOB_ISA + "," + JOB_REMOTE + "]";
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, replay, 2, 2);
            stubSearch("desenvolvedor junior", 2, replay, 2, 2);

            var jobs = provider(2).extract();

            assertEquals(2, jobs.size());
            verify(1, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo("2"))));
            verify(0, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.offset", equalTo("4"))));
        }
        @Test
        @DisplayName("extract should cap merged jobs at max-jobs")
        void extract_whenMergedJobsExceedMaxJobs_shouldCapAtMaxJobs() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "," + JOB_REMOTE + "]", 509, 2);
            stubSearch("desenvolvedor junior", 2, "[" + PAGE_3 + "]", 509, 2);

            var jobs = provider(List.of("desenvolvedor junior"), 2, 2).extract();

            assertEquals(2, jobs.size(), "max-jobs caps the merged result");
        }
    }

    // ---------------------------------------------------------- workModel matrix

    @Nested
    @DisplayName("workplaceType matrix")
    class WorkModelMatrix {

        @Test
        @DisplayName("extract should map remote/hybrid/on-site/absent to the shared work-model vocabulary")
        void extract_whenWorkplaceTypeVariants_shouldMapToCanonicalLabels() {
            var onSite = variant(JOB_ISA, 2, "https://onsite.gupy.io/job/2?jobBoardSource=mcp_candidate",
                    "on-site", "Analista Presencial");
            var absent = variant(JOB_ISA, 3, "https://desconhecido.gupy.io/job/3?jobBoardSource=mcp_candidate",
                    null, "Analista Sem modalidade");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0,
                    "[" + JOB_REMOTE + "," + JOB_ISA + "," + onSite + "," + absent + "]", 509, 4);
            stubSearch("desenvolvedor junior", 4, "[]", 509, 4);

            var jobs = provider(4).extract();

            assertEquals(4, jobs.size());
            assertEquals("Remoto", jobs.get(0).workModel());
            assertEquals("Híbrido", jobs.get(1).workModel());
            assertEquals("Presencial", jobs.get(2).workModel(), "\"on-site\" → Presencial");
            assertNull(jobs.get(3).workModel(), "absent workplaceType → null, never invented");
        }
    }

    // ------------------------------------------------------------- company name

    @Nested
    @DisplayName("careerPageName suffix handling")
    class CompanyName {

        @Test
        @DisplayName("extract should strip an aggregator suffix such as \" - Linkedin\"")
        void extract_whenCareerPageNameHasLinkedinSuffix_shouldStripIt() {
            var suffixed = object(JOB_ISA);
            suffixed.put("careerPageName", "TechCo - Linkedin");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + suffixed + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals(1, jobs.size());
            assertEquals("TechCo", jobs.get(0).company());
        }

        @Test
        @DisplayName("extract should keep a legitimate dash company name intact")
        void extract_whenCareerPageNameIsLegitDashName_shouldKeepIt() {
            // Live: "TMSA - TECNOLOGIA EM MOVIMENTAÇÃO S/A" is the company's real name.
            var real = object(JOB_ISA);
            real.put("careerPageName", "TMSA - TECNOLOGIA EM MOVIMENTAÇÃO S/A");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + real + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals("TMSA - TECNOLOGIA EM MOVIMENTAÇÃO S/A", jobs.get(0).company());
        }
    }

    // ------------------------------------------------------ location and date

    @Nested
    @DisplayName("Location composition and dates")
    class LocationAndDate {

        @Test
        @DisplayName("extract should append a non-BR country to the location")
        void extract_whenCountryIsNotBrazil_shouldAppendCountry() {
            var abroad = object(JOB_ISA);
            abroad.put("country", "Portugal");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + abroad + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals("São Paulo, São Paulo, Portugal", jobs.get(0).location());
        }

        @Test
        @DisplayName("extract should use the city alone when state is absent")
        void extract_whenStateMissing_shouldUseCityAlone() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + JOB_REMOTE + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals("Recife", jobs.get(0).location());
        }

        @Test
        @DisplayName("extract should keep a published date that is already day-precision")
        void extract_whenPublishedDateHasNoTime_shouldKeepItAsIs() {
            var dated = object(JOB_ISA);
            dated.put("publishedDate", "2026-09-08");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + dated + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals("2026-09-08", jobs.get(0).rawDate());
        }
    }

    // ------------------------------------------------------------ error policy

    @Nested
    @DisplayName("Error policy (per query)")
    class Errors {

        @Test
        @DisplayName("extract should skip a query that returns HTTP 500 and continue with the next keyword")
        void extract_whenServerReturns500_shouldSkipQueryAndContinue() {
            stubInitialize();
            stubFor(post(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("quebrado")))
                    .willReturn(aResponse().withStatus(500)));
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);

            var jobs = provider(List.of("quebrado", "desenvolvedor junior"), 100, 200).extract();

            assertEquals(1, jobs.size(), "a dead query is skipped, the fetch still succeeds");
            assertEquals("Desenvolvedor Junior", jobs.get(0).title());
        }

        @Test
        @DisplayName("extract should retry a 429 then skip the query, never failing the fetch")
        void extract_whenServerReturns429_shouldRetryThenSkipQuery() {
            stubInitialize();
            stubFor(post(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("limitado")))
                    .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);

            var jobs = provider(List.of("limitado", "desenvolvedor junior"), 100, 200).extract();

            assertEquals(1, jobs.size());
            // 429 must be retried once (maxAttempts=2) before the query is skipped.
            verify(2, postRequestedFor(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("limitado"))));
        }

        @Test
        @DisplayName("extract should skip a query whose MCP result is a tool error")
        void extract_whenToolErrorEnvelope_shouldSkipQuery() {
            stubInitialize();
            stubFor(post(urlPathEqualTo(MCP_PATH))
                    .withRequestBody(matchingJsonPath("$.params.arguments.term", equalTo("invalido")))
                    .willReturn(sseOk(sse(envelope(
                            "MCP error -32602: Input validation error: Invalid arguments for tool search_jobs")))));
            stubSearch("desenvolvedor junior", 0, "[" + JOB_ISA + "]", 509, 100);

            var jobs = provider(List.of("invalido", "desenvolvedor junior"), 100, 200).extract();

            assertEquals(1, jobs.size());
        }

        @Test
        @DisplayName("extract should return an empty list when no keyword matches")
        void extract_whenNoResults_shouldReturnEmptyList() {
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[]", 0, 100);

            assertTrue(provider(100).extract().isEmpty(),
                    "an empty result is a valid empty list, never null");
        }
    }

    // ------------------------------------------------------------- entry skips

    @Nested
    @DisplayName("Entry skips")
    class Skips {

        @Test
        @DisplayName("extract should skip an entry with a blank title")
        void extract_whenTitleBlank_shouldSkipEntry() {
            var untitled = object(JOB_ISA);
            untitled.put("name", "   ");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + untitled + "," + JOB_REMOTE + "]", 509, 100);

            var jobs = provider(100).extract();

            assertEquals(1, jobs.size());
            assertEquals("Dev Frontend Remoto", jobs.get(0).title());
        }

        @Test
        @DisplayName("extract should skip an entry with a blank jobUrl")
        void extract_whenJobUrlBlank_shouldSkipEntry() {
            var noUrl = object(JOB_ISA);
            noUrl.put("jobUrl", "");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + noUrl + "]", 509, 100);

            assertTrue(provider(100).extract().isEmpty(),
                    "a blank jobUrl is a skip, not a fallback to careerPageUrl");
        }
    }

    // ------------------------------------------------------- #74 parity check

    @Nested
    @DisplayName("Detail-page company domains (#74 machinery unchanged)")
    class DetailPageCompanyDomains {

        @Test
        @DisplayName("extract should resolve companyWebsite from one detail page per job-URL host")
        void extract_whenDetailPageHasCompanyLink_shouldAttachWebsite() {
            var localUrl = "http://localhost:" + port + "/gupy/techco/job/1?jobBoardSource=mcp_candidate";
            var job = variant(JOB_ISA, 1, localUrl, "hybrid", "Desenvolvedor Java");
            stubInitialize();
            stubSearch("desenvolvedor junior", 0, "[" + job + "]", 509, 100);
            stubFor(get(urlPathEqualTo("/gupy/techco/job/1"))
                    .willReturn(ok("""
                        <html><body>
                          <a href="https://www.linkedin.com/company/techco">LinkedIn</a>
                          <a href="https://www.techco.com.br/">Site</a>
                        </body></html>
                        """)));

            var resolver = new HttpCompanyDomainResolver(RestClient.builder().baseUrl(baseUrl).build(),
                    retry, new TokenBucketRateLimiter(100, 10, Map.of()));
            var jobs = new GupyMcpProvider(mcpUrl, 5, List.of("desenvolvedor junior"), 100, 200,
                    retry, resolver, 100).extract();

            assertEquals(1, jobs.size());
            assertEquals("https://www.techco.com.br", jobs.get(0).metadata().get("companyWebsite"),
                    "the #74 detail-page resolution must work unchanged on MCP job URLs");
            verify(1, getRequestedFor(urlPathEqualTo("/gupy/techco/job/1")));
        }
    }
}