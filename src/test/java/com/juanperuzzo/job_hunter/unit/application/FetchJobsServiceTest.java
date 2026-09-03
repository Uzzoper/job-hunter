package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.port.out.ScraperResult;
import com.juanperuzzo.job_hunter.application.service.FetchJobsService;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FetchJobsService tests")
class FetchJobsServiceTest {

    @Mock
    private ScraperPort scraperPort;

    @Mock
    private JobRepository jobRepository;

    private FetchJobsService fetchJobsService;

    @BeforeEach
    void setUp() {
        fetchJobsService = new FetchJobsService(scraperPort, jobRepository);
    }

    @Nested
    @DisplayName("Scenario 1: new jobs found")
    class NewJobsFoundTests {

        @Test
        @DisplayName("fetchAndSave should save new jobs and skip existing ones when scraper returns jobs")
        void fetchAndSave_whenNewJobsFound_shouldSaveOnlyNewJobs() {
            var newJob = new Job(null, "Java Developer", "Company A", "https://example.com/job/1", "Description", LocalDate.now(), "gupy");
            var existingJob = new Job(null, "Java Developer", "Company B", "https://example.com/job/2", "Description", LocalDate.now(), "gupy");

            when(scraperPort.fetch()).thenReturn(new ScraperResult(
                    List.of(newJob, existingJob),
                    List.of(new ProviderFetchStats("gupy", 2, 0, 0, 0, null))));
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(false);
            when(jobRepository.existsByUrl("https://example.com/job/2")).thenReturn(true);

            var result = fetchJobsService.fetchAndSave();

            verify(jobRepository, times(1)).save(newJob);
            verify(jobRepository, never()).save(existingJob);
            assertEquals(2, result.totalFetched());
            assertEquals(1, result.totalSaved());
            assertEquals(1, result.perProvider().get(0).saved());
        }
    }

    @Nested
    @DisplayName("Scenario 2: no new jobs")
    class NoNewJobsTests {

        @Test
        @DisplayName("fetchAndSave should not save any job when all jobs already exist")
        void fetchAndSave_whenNoNewJobs_shouldNotSaveAnyJob() {
            var existingJob = new Job(null, "Java Developer", "Company A", "https://example.com/job/1", "Description", LocalDate.now(), "gupy");

            when(scraperPort.fetch()).thenReturn(new ScraperResult(
                    List.of(existingJob),
                    List.of(new ProviderFetchStats("gupy", 1, 0, 0, 0, null))));
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(true);

            var result = fetchJobsService.fetchAndSave();

            verify(jobRepository, never()).save(any());
            assertEquals(0, result.totalSaved());
            assertEquals(0, result.totalWithEmail());
        }
    }

    @Nested
    @DisplayName("Scenario 3: scraper fails")
    class ScraperFailsTests {

        @Test
        @DisplayName("fetchAndSave should propagate ScraperException when scraper fails")
        void fetchAndSave_whenScraperFails_shouldPropagateException() {
            when(scraperPort.fetch()).thenThrow(new ScraperException("Scraping failed"));

            try {
                fetchJobsService.fetchAndSave();
            } catch (ScraperException e) {
                // expected
            }

            verify(jobRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Scenario 4: empty list returned")
    class EmptyListTests {

        @Test
        @DisplayName("fetchAndSave should not save any job when scraper returns empty list")
        void fetchAndSave_whenEmptyListReturned_shouldNotSaveAnyJob() {
            when(scraperPort.fetch()).thenReturn(new ScraperResult(List.of(), List.of()));

            var result = fetchJobsService.fetchAndSave();

            verify(jobRepository, never()).save(any());
            assertEquals(0, result.totalFetched());
            assertEquals(0, result.totalSaved());
        }
    }

    @Nested
    @DisplayName("Async enrichment contract: fetch must not enrich")
    class NoEnrichmentContractTests {

        @Test
        @DisplayName("fetchAndSave should not call CompanySiteEnrichment and return FetchResult unchanged")
        void fetchAndSave_shouldNotEnrichAndReturnFast() {
            var jobWithWebsite = new Job(null, "Java Dev", "Acme", "https://example.com/job/1",
                    "Description", LocalDate.now(), "gupy", null, "https://acme.com");

            when(scraperPort.fetch()).thenReturn(new ScraperResult(
                    List.of(jobWithWebsite),
                    List.of(new ProviderFetchStats("gupy", 1, 1, 0, 0, null))));
            when(jobRepository.existsByUrl(any())).thenReturn(false);

            var result = fetchJobsService.fetchAndSave();

            // FetchResult shape unchanged: totalFetched/totalSaved/totalWithEmail/perProvider.
            assertEquals(1, result.totalFetched());
            assertEquals(1, result.totalSaved());
            assertEquals(0, result.totalWithEmail());
            assertEquals(1, result.perProvider().size());

            // The persisted job must not have been enriched (no company-site contribution).
            verify(jobRepository).save(argThat(j -> j.contactEmail() == null));
        }
    }

    @Nested
    @DisplayName("Scenario 17: totalWithEmail counts persisted jobs with email")
    class FetchResultStatsTests {

        @Test
        @DisplayName("fetchAndSave should report totalWithEmail based on saved jobs with contactEmail")
        void fetchAndSave_whenJobHasEmail_shouldCountWithEmail() {
            var jobWithEmail = new Job(null, "Java Dev", "Acme", "https://example.com/job/1",
                    "Description", LocalDate.now(), "gupy", "rh@acme.com", "https://acme.com");
            var jobWithoutEmail = new Job(null, "React Dev", "Beta", "https://example.com/job/2",
                    "Description", LocalDate.now(), "gupy");

            when(scraperPort.fetch()).thenReturn(new ScraperResult(
                    List.of(jobWithEmail, jobWithoutEmail),
                    List.of(new ProviderFetchStats("gupy", 2, 0, 0, 0, null))));
            when(jobRepository.existsByUrl(any())).thenReturn(false);

            var result = fetchJobsService.fetchAndSave();

            assertEquals(2, result.totalSaved());
            assertEquals(1, result.totalWithEmail());
            assertEquals(2, result.perProvider().get(0).saved());
            assertEquals(1, result.perProvider().get(0).withEmail());
        }
    }
}