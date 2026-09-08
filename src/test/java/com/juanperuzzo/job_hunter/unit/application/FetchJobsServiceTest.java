package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.in.JobWithDraftStatus;
import com.juanperuzzo.job_hunter.application.port.in.ProviderFetchStats;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.port.out.ScraperResult;
import com.juanperuzzo.job_hunter.application.service.FetchJobsService;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Mock
    private EmailDraftRepository emailDraftRepository;

    private FetchJobsService fetchJobsService;

    @BeforeEach
    void setUp() {
        fetchJobsService = new FetchJobsService(scraperPort, jobRepository, emailDraftRepository);
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
                    List.of(new ProviderFetchStats("gupy", 2, 0, 0, 0, 0, null))));
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
                    List.of(new ProviderFetchStats("gupy", 1, 0, 0, 0, 0, null))));
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
                    List.of(new ProviderFetchStats("gupy", 1, 1, 0, 0, 0, null))));
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
                    List.of(new ProviderFetchStats("gupy", 2, 0, 0, 0, 0, null))));
            when(jobRepository.existsByUrl(any())).thenReturn(false);

            var result = fetchJobsService.fetchAndSave();

            assertEquals(2, result.totalSaved());
            assertEquals(1, result.totalWithEmail());
            assertEquals(2, result.perProvider().get(0).saved());
            assertEquals(1, result.perProvider().get(0).withEmail());
        }
    }

    @Nested
    @DisplayName("Scenario 5: per-job draft status and excludeApplied")
    class DraftStatusListingTests {

        private static final long USER_ID = 7L;

        private Job job(long id, String title) {
            return new Job(id, title, "Company", "https://example.com/job/" + id,
                    "Description", LocalDate.now(), "gupy");
        }

        private EmailDraft draft(long jobId, EmailStatus status) {
            return new EmailDraft(100L + jobId, jobId, USER_ID, "Subject", "Body",
                    status, LocalDateTime.now());
        }

        @Test
        @DisplayName("findAllWithDraftStatus should carry SENT status when the user already applied")
        void findAllWithDraftStatus_whenJobHasSentDraft_shouldCarrySentStatus() {
            var appliedJob = job(1L, "Java Dev");
            var freshJob = job(2L, "React Dev");
            when(jobRepository.findAll()).thenReturn(List.of(appliedJob, freshJob));
            when(emailDraftRepository.findByJobIdAndUserId(1L, USER_ID))
                    .thenReturn(Optional.of(draft(1L, EmailStatus.SENT)));
            when(emailDraftRepository.findByJobIdAndUserId(2L, USER_ID)).thenReturn(Optional.empty());

            var result = fetchJobsService.findAllWithDraftStatus(USER_ID, null, false);

            assertEquals(2, result.size());
            assertEquals(EmailStatus.SENT, result.get(0).draftStatus());
            assertNull(result.get(1).draftStatus());
        }

        @Test
        @DisplayName("findAllWithDraftStatus should drop jobs with a SENT draft when excludeApplied=true")
        void findAllWithDraftStatus_whenExcludeAppliedAndSentDraft_shouldDropAppliedJob() {
            var appliedJob = job(1L, "Java Dev");
            var freshJob = job(2L, "React Dev");
            when(jobRepository.findAll()).thenReturn(List.of(appliedJob, freshJob));
            when(emailDraftRepository.findByJobIdAndUserId(1L, USER_ID))
                    .thenReturn(Optional.of(draft(1L, EmailStatus.SENT)));
            when(emailDraftRepository.findByJobIdAndUserId(2L, USER_ID)).thenReturn(Optional.empty());

            var result = fetchJobsService.findAllWithDraftStatus(USER_ID, null, true);

            assertEquals(1, result.size());
            assertEquals(2L, result.get(0).job().id());
            assertNull(result.get(0).draftStatus());
        }

        @Test
        @DisplayName("findAllWithDraftStatus should keep PENDING, APPROVED and jobless entries when excludeApplied=true")
        void findAllWithDraftStatus_whenExcludeAppliedAndPendingApprovedOrNoDraft_shouldKeepAll() {
            var pendingJob = job(1L, "Java Dev");
            var approvedJob = job(2L, "React Dev");
            var freshJob = job(3L, "Go Dev");
            when(jobRepository.findAll()).thenReturn(List.of(pendingJob, approvedJob, freshJob));
            when(emailDraftRepository.findByJobIdAndUserId(1L, USER_ID))
                    .thenReturn(Optional.of(draft(1L, EmailStatus.PENDING)));
            when(emailDraftRepository.findByJobIdAndUserId(2L, USER_ID))
                    .thenReturn(Optional.of(draft(2L, EmailStatus.APPROVED)));
            when(emailDraftRepository.findByJobIdAndUserId(3L, USER_ID)).thenReturn(Optional.empty());

            var result = fetchJobsService.findAllWithDraftStatus(USER_ID, null, true);

            assertEquals(3, result.size());
            assertEquals(Arrays.asList(EmailStatus.PENDING, EmailStatus.APPROVED, null),
                    result.stream().map(JobWithDraftStatus::draftStatus).toList());
        }

        @Test
        @DisplayName("findAllWithDraftStatus should keep a job whose SENT draft belongs to another user")
        void findAllWithDraftStatus_whenSentDraftBelongsToOtherUser_shouldKeepJob() {
            var job = job(1L, "Java Dev");
            when(jobRepository.findAll()).thenReturn(List.of(job));
            // The draft exists but belongs to another user: the current-user lookup
            // (findByJobIdAndUserId(1L, USER_ID)) yields no draft, so the job is
            // neither marked as applied nor excluded.
            when(emailDraftRepository.findByJobIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

            var result = fetchJobsService.findAllWithDraftStatus(USER_ID, null, true);

            assertEquals(1, result.size());
            assertNull(result.get(0).draftStatus());
        }

        @Test
        @DisplayName("findAllWithDraftStatus should combine hasEmail filter with excludeApplied")
        void findAllWithDraftStatus_whenHasEmailAndExcludeApplied_shouldApplyBoth() {
            var appliedJobWithEmail = new Job(1L, "Java Dev", "Acme", "https://example.com/job/1",
                    "Description", LocalDate.now(), "gupy", "rh@acme.com");
            when(jobRepository.findAllByContactEmailIsNotNull()).thenReturn(List.of(appliedJobWithEmail));
            when(emailDraftRepository.findByJobIdAndUserId(1L, USER_ID))
                    .thenReturn(Optional.of(draft(1L, EmailStatus.SENT)));

            var result = fetchJobsService.findAllWithDraftStatus(USER_ID, true, true);

            assertTrue(result.isEmpty());
            verify(jobRepository).findAllByContactEmailIsNotNull();
            verify(jobRepository, never()).findAll();
        }
    }
}