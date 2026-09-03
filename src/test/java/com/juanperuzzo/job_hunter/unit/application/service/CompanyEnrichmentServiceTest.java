package com.juanperuzzo.job_hunter.unit.application.service;

import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.CompanyEnrichmentService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyEnrichmentService tests")
class CompanyEnrichmentServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private CompanySiteEnrichmentPort enricher;

    private CompanyEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new CompanyEnrichmentService(jobRepository, enricher);
    }

    private static Job job(Long id, String website) {
        return new Job(id, "Dev", "Co", "https://example.com/job/" + id, "desc",
                LocalDate.now(), "gupy", null, website);
    }

    @Nested
    @DisplayName("Scenario 2: batch enrichment by distinct domain")
    class SharedDomainTests {

        @Test
        @DisplayName("enrichMissingEmails should crawl shared domain once and update all jobs")
        void enrich_whenSharedDomain_shouldCrawlOnceAndUpdateAll() {
            var j1 = job(1L, "https://acme.com/jobs");
            var j2 = job(2L, "https://acme.com/careers");
            var j3 = job(3L, "https://acme.com/");

            when(jobRepository.findJobsNeedingEnrichment(50)).thenReturn(List.of(j1, j2, j3));

            // Enricher applies the email to the representative job only (single crawl).
            when(enricher.enrich(any(Job.class))).thenAnswer(inv -> {
                var job = inv.getArgument(0, Job.class);
                return new Job(job.id(), job.title(), job.company(), job.url(), job.description(),
                        job.postedAt(), job.source(), "rh@acme.com", job.companyWebsite());
            });

            var result = service.enrichMissingEmails(50);

            assertEquals(3, result.checked());
            assertEquals(3, result.enriched());
            assertEquals(0, result.skippedPortal());
            assertEquals(0, result.failed());

            // Single crawl per distinct domain.
            verify(enricher, times(1)).enrich(any(Job.class));
            // Each updated job is persisted in place.
            verify(jobRepository, times(3)).save(any(Job.class));
        }
    }

    @Nested
    @DisplayName("Scenario 3: portal domains skipped without HTTP")
    class PortalDomainTests {

        @Test
        @DisplayName("enrichMissingEmails should skip portal domains without crawling")
        void enrich_whenPortalDomain_shouldSkipWithoutCrawl() {
            var j1 = job(1L, "https://company1.gupy.io/jobs");

            when(jobRepository.findJobsNeedingEnrichment(50)).thenReturn(List.of(j1));

            var result = service.enrichMissingEmails(50);

            assertEquals(1, result.checked());
            assertEquals(0, result.enriched());
            assertEquals(1, result.skippedPortal());
            assertEquals(0, result.failed());

            verify(enricher, never()).enrich(any(Job.class));
            verify(jobRepository, never()).save(any(Job.class));
        }
    }

    @Nested
    @DisplayName("Scenario 4: per-domain failure is non-fatal")
    class FailureTests {

        @Test
        @DisplayName("enrichMissingEmails should count failed domain and continue with others")
        void enrich_whenDomainFails_shouldCountFailedAndContinue() {
            var jA = job(1L, "https://fail.com/");
            var jB = job(2L, "https://good.com/");

            when(jobRepository.findJobsNeedingEnrichment(50)).thenReturn(List.of(jA, jB));

            // Domain A returns unchanged (failure), domain B enriches.
            when(enricher.enrich(any(Job.class))).thenAnswer(inv -> {
                var job = inv.getArgument(0, Job.class);
                if (job.companyWebsite() != null && job.companyWebsite().contains("fail.com")) {
                    return job; // enriched but unchanged => represents no email found / failure
                }
                return new Job(job.id(), job.title(), job.company(), job.url(), job.description(),
                        job.postedAt(), job.source(), "rh@good.com", job.companyWebsite());
            });

            var result = service.enrichMissingEmails(50);

            assertEquals(2, result.checked());
            assertEquals(1, result.enriched());
            assertEquals(0, result.skippedPortal());
            assertEquals(1, result.failed());
        }
    }

    @Nested
    @DisplayName("Scenario 5: limit bounds the batch")
    class LimitTests {

        @Test
        @DisplayName("enrichMissingEmails should check at most the limit of candidates")
        void enrich_whenLimitBounds_shouldCheckAtMostLimit() {
            var jobs = List.of(
                    job(1L, "https://a.com/"),
                    job(2L, "https://b.com/"),
                    job(3L, "https://c.com/"),
                    job(4L, "https://d.com/"),
                    job(5L, "https://e.com/"));

            when(jobRepository.findJobsNeedingEnrichment(2)).thenReturn(jobs.subList(0, 2));

            var result = service.enrichMissingEmails(2);

            assertEquals(2, result.checked());
            assertEquals(2, result.checked()); // exact bounded count
        }
    }

    @Nested
    @DisplayName("Scenario: no candidates")
    class NoCandidatesTests {

        @Test
        @DisplayName("enrichMissingEmails should return zeros when no candidates")
        void enrich_whenNoCandidates_shouldReturnZeros() {
            when(jobRepository.findJobsNeedingEnrichment(50)).thenReturn(List.of());

            var result = service.enrichMissingEmails(50);

            assertEquals(0, result.checked());
            assertEquals(0, result.enriched());
            assertEquals(0, result.skippedPortal());
            assertEquals(0, result.failed());

            verify(enricher, never()).enrich(any(Job.class));
            verify(jobRepository, never()).save(any(Job.class));
        }
    }
}
