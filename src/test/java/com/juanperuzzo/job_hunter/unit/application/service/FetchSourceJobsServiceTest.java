package com.juanperuzzo.job_hunter.unit.application.service;

import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.application.port.out.SourceFetchPort;
import com.juanperuzzo.job_hunter.application.service.FetchSourceJobsService;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FetchSourceJobsService tests")
class FetchSourceJobsServiceTest {

    @Mock
    private SourceFetchPort sourceFetchPort;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobNormalizer normalizer;

    private FetchSourceJobsService service;

    @BeforeEach
    void setUp() {
        service = new FetchSourceJobsService(sourceFetchPort, jobRepository, normalizer);
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

            when(sourceFetchPort.fetch("gupy")).thenReturn(List.of(rawJob));
            when(normalizer.normalize(rawJob)).thenReturn(job);
            when(jobRepository.existsByUrl("https://example.com/job/1")).thenReturn(false);

            service.fetchAndSave("gupy");

            verify(jobRepository, times(1)).save(job);
        }
    }

    @Nested
    @DisplayName("Scenario 2: provider fails")
    class ProviderFailsTests {

        @Test
        @DisplayName("fetchAndSave should throw ScraperException when source fetch fails")
        void fetchAndSave_whenProviderFails_shouldThrowScraperException() {
            when(sourceFetchPort.fetch("gupy")).thenThrow(new ScraperException("Failed to fetch from gupy"));

            try {
                service.fetchAndSave("gupy");
            } catch (ScraperException e) {
                // expected
            }

            verify(jobRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Scenario 3: empty source response")
    class EmptySourceTests {

        @Test
        @DisplayName("fetchAndSave should not save any job when source returns empty list")
        void fetchAndSave_whenEmpty_shouldReturnEmptyList() {
            when(sourceFetchPort.fetch("gupy")).thenReturn(List.of());

            service.fetchAndSave("gupy");

            verify(jobRepository, never()).save(any());
        }
    }
}
