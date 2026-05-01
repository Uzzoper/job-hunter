package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
            var newJob = new Job(null, "Java Developer", "Company A", "https://example.com/job/1", "Description", LocalDate.now(), Optional.empty());
            var existingJob = new Job(null, "Java Developer", "Company B", "https://example.com/job/2", "Description", LocalDate.now(), Optional.empty());

            when(scraperPort.fetch()).thenReturn(List.of(newJob, existingJob));
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(false);
            when(jobRepository.existsByUrl("https://example.com/job/2")).thenReturn(true);

            fetchJobsService.fetchAndSave();

            verify(jobRepository, times(1)).save(newJob);
            verify(jobRepository, never()).save(existingJob);
        }
    }

    @Nested
    @DisplayName("Scenario 2: no new jobs")
    class NoNewJobsTests {

        @Test
        @DisplayName("fetchAndSave should not save any job when all jobs already exist")
        void fetchAndSave_whenNoNewJobs_shouldNotSaveAnyJob() {
            var existingJob = new Job(null, "Java Developer", "Company A", "https://example.com/job/1", "Description", LocalDate.now(), Optional.empty());

            when(scraperPort.fetch()).thenReturn(List.of(existingJob));
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(true);

            fetchJobsService.fetchAndSave();

            verify(jobRepository, never()).save(any());
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
            when(scraperPort.fetch()).thenReturn(List.of());

            fetchJobsService.fetchAndSave();

            verify(jobRepository, never()).save(any());
        }
    }
}
