package com.juanperuzzo.job_hunter.unit.application.service;

import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.KeywordResolver;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.application.port.out.SourceFetchPort;
import com.juanperuzzo.job_hunter.application.service.FetchSourceJobsService;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.application.port.out.NormalizerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FetchSourceJobsService tests")
class FetchSourceJobsServiceTest {

    private static final long USER_ID = 7L;

    @Mock
    private SourceFetchPort sourceFetchPort;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private NormalizerPort normalizer;

    @Mock
    private KeywordResolver keywordResolver;

    private FetchSourceJobsService service;

    @BeforeEach
    void setUp() {
        service = new FetchSourceJobsService(sourceFetchPort, jobRepository, normalizer, keywordResolver);
        lenient().when(keywordResolver.resolve(any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("Scenario 1: source returns valid jobs")
    class ValidSourceTests {

        @Test
        @DisplayName("fetchAndSave should save jobs when source returns valid raw jobs that normalize successfully")
        void fetchAndSave_whenValidSource_shouldReturnSavedJobs() {
            var rawJob = new RawJob(
                    "Java Developer", "Company A", "https://example.com/job/1",
                    "Description", "2025-01-01", "Remote", "Home Office",
                    "gupy", null
            );
            var job = new Job(null, "Java Developer", "Company A",
                    "https://example.com/job/1", "Description",
                    LocalDate.now(), "gupy");

            when(sourceFetchPort.fetch(eq("gupy"), anyList(), any())).thenReturn(List.of(rawJob));
            when(normalizer.normalize(eq(rawJob), anyList())).thenReturn(job);
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(false);

            var result = service.fetchAndSave(USER_ID, "gupy");

            verify(jobRepository, times(1)).save(job);
            assertEquals(1, result.totalFetched());
            assertEquals(1, result.totalSaved());
            assertEquals(1, result.perProvider().size());
            assertEquals("gupy", result.perProvider().get(0).source());
            assertEquals(null, result.perProvider().get(0).error());
        }
    }

    @Nested
    @DisplayName("Scenario 2: provider fails")
    class ProviderFailsTests {

        @Test
        @DisplayName("fetchAndSave should capture the error in FetchResult instead of failing the request")
        void fetchAndSave_whenProviderFails_shouldReportError() {
            when(sourceFetchPort.fetch(eq("gupy"), anyList(), any())).thenThrow(new ScraperException("Failed to fetch from gupy"));

            var result = service.fetchAndSave(USER_ID, "gupy");

            verify(jobRepository, never()).save(any());
            assertEquals(0, result.totalSaved());
            assertEquals(1, result.perProvider().size());
            assertNotNull(result.perProvider().get(0).error());
            assertEquals("Failed to fetch from gupy", result.perProvider().get(0).error());
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty source response")
    class EmptySourceTests {

        @Test
        @DisplayName("fetchAndSave should not save any job when source returns empty list")
        void fetchAndSave_whenEmpty_shouldReturnEmptyList() {
            when(sourceFetchPort.fetch(eq("gupy"), anyList(), any())).thenReturn(List.of());

            var result = service.fetchAndSave(USER_ID, "gupy");

            verify(jobRepository, never()).save(any());
            assertEquals(0, result.totalFetched());
            assertEquals(0, result.totalSaved());
            assertEquals(1, result.perProvider().size());
        }
    }

    @Nested
    @DisplayName("Scenario 17: withEmail and detailFailedCount reporting")
    class StatsTests {

        @Test
        @DisplayName("fetchAndSave should report withEmail count when enriched jobs carry emails")
        void fetchAndSave_whenJobsHaveEmail_shouldCountWithEmail() {
            var rawWithoutEmail = new RawJob(
                    "Java Developer", "Company A", "https://example.com/job/1",
                    "Description", "2025-01-01", null, null, "gupy", null);
            var jobWithEmail = new Job(null, "Java Developer", "Company A",
                    "https://example.com/job/1", "Description",
                    LocalDate.now(), "gupy", "rh@acme.com", null);

            when(sourceFetchPort.fetch(eq("gupy"), anyList(), any())).thenReturn(List.of(rawWithoutEmail));
            when(normalizer.normalize(eq(rawWithoutEmail), anyList())).thenReturn(jobWithEmail);
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(false);

            var result = service.fetchAndSave(USER_ID, "gupy");

            assertEquals(1, result.totalWithEmail());
            assertEquals(1, result.perProvider().get(0).withEmail());
        }
    }

    @Nested
    @DisplayName("Scenario: runtime keyword resolution")
    class KeywordResolutionTests {

        @Test
        @DisplayName("fetchAndSave should resolve the user's keywords and pass them to source and normalizer")
        void fetchAndSave_whenKeywordsStored_shouldPassResolvedKeywordsDownstream() {
            var rawJob = new RawJob(
                    "Rust Developer", "Company A", "https://example.com/job/1",
                    "Description", "2025-01-01", null, null, "gupy", null);
            var job = new Job(null, "Rust Developer", "Company A",
                    "https://example.com/job/1", "Description",
                    LocalDate.now(), "gupy");

            when(keywordResolver.resolve(USER_ID)).thenReturn(List.of("rust", "kotlin"));
            when(sourceFetchPort.fetch(eq("gupy"), eq(List.of("rust", "kotlin")), any())).thenReturn(List.of(rawJob));
            when(normalizer.normalize(rawJob, List.of("rust", "kotlin"))).thenReturn(job);
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(false);

            service.fetchAndSave(USER_ID, "gupy");

            verify(sourceFetchPort).fetch(eq("gupy"), eq(List.of("rust", "kotlin")), any());
            verify(normalizer).normalize(rawJob, List.of("rust", "kotlin"));
        }

        @Test
        @DisplayName("fetchAndSave should wire the known-URL predicate from jobRepository::existsByUrl")
        void fetchAndSave_whenResolvingKnownUrls_shouldDelegateToExistsByUrl() {
            when(sourceFetchPort.fetch(eq("gupy"), anyList(), any())).thenReturn(List.of());

            service.fetchAndSave(USER_ID, "gupy");

            var predicateCaptor = ArgumentCaptor.forClass(Predicate.class);
            verify(sourceFetchPort).fetch(eq("gupy"), anyList(), predicateCaptor.capture());
            @SuppressWarnings("unchecked")
            var isKnownUrl = (Predicate<String>) predicateCaptor.getValue();

            when(jobRepository.existsByUrl("https://example.com/known")).thenReturn(true);

            assertTrue(isKnownUrl.test("https://example.com/known"),
                    "the wired predicate must delegate to jobRepository.existsByUrl");
            verify(jobRepository).existsByUrl("https://example.com/known");
        }
    }
}