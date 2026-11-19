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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

            when(jobRepository.findJobsNeedingEnrichment(eq(50), any(LocalDate.class))).thenReturn(List.of(j1, j2, j3));

            // Enricher applies the email to the representative job only (single crawl).
            when(enricher.enrich(any(Job.class))).thenAnswer(inv -> {
                var job = inv.getArgument(0, Job.class);
                return job.withContactEmail("rh@acme.com");
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

            when(jobRepository.findJobsNeedingEnrichment(eq(50), any(LocalDate.class))).thenReturn(List.of(j1));

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

            when(jobRepository.findJobsNeedingEnrichment(eq(50), any(LocalDate.class))).thenReturn(List.of(jA, jB));

            // Domain A throws (crawl failure), domain B enriches.
            when(enricher.enrich(any(Job.class))).thenAnswer(inv -> {
                var job = inv.getArgument(0, Job.class);
                if (job.companyWebsite() != null && job.companyWebsite().contains("fail.com")) {
                    throw new RuntimeException("crawl failed");
                }
                return job.withContactEmail("rh@good.com");
            });

            var result = service.enrichMissingEmails(50);

            assertEquals(2, result.checked());
            assertEquals(1, result.enriched());
            assertEquals(0, result.skippedPortal());
            assertEquals(1, result.failed());
        }

        @Test
        @DisplayName("enrichMissingEmails should NOT count a no-email domain as failed")
        void enrich_whenNoEmailFound_shouldNotCountAsFailed() {
            var jA = job(1L, "https://noemail.com/");
            var jB = job(2L, "https://good.com/");

            when(jobRepository.findJobsNeedingEnrichment(eq(50), any(LocalDate.class))).thenReturn(List.of(jA, jB));

            // Domain A enriches to no email (crawl succeeded but nothing found),
            // domain B enriches.
            when(enricher.enrich(any(Job.class))).thenAnswer(inv -> {
                var job = inv.getArgument(0, Job.class);
                if (job.companyWebsite() != null && job.companyWebsite().contains("noemail.com")) {
                    return job; // unchanged => no email found, NOT a failure
                }
                return job.withContactEmail("rh@good.com");
            });

            var result = service.enrichMissingEmails(50);

            assertEquals(2, result.checked());
            assertEquals(1, result.enriched());
            assertEquals(0, result.skippedPortal());
            assertEquals(0, result.failed());
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

            when(jobRepository.findJobsNeedingEnrichment(eq(2), any(LocalDate.class))).thenReturn(jobs.subList(0, 2));

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
            when(jobRepository.findJobsNeedingEnrichment(eq(50), any(LocalDate.class))).thenReturn(List.of());

            var result = service.enrichMissingEmails(50);

            assertEquals(0, result.checked());
            assertEquals(0, result.enriched());
            assertEquals(0, result.skippedPortal());
            assertEquals(0, result.failed());

            verify(enricher, never()).enrich(any(Job.class));
            verify(jobRepository, never()).save(any(Job.class));
        }
    }

    @Nested
    @DisplayName("Phase 2: batch time budgets and concurrency")
    class BatchBudgetTests {

        @Test
        @DisplayName("a slow domain should time out while another domain still enriches")
        void enrich_whenSlowDomainExceedsBudget_shouldCountFailedAndContinue() {
            var slow = job(1L, "https://slow.example/");
            var slowSibling = job(2L, "https://slow.example/contact");
            var fast = job(3L, "https://fast.example/");
            when(jobRepository.findJobsNeedingEnrichment(eq(3), any(LocalDate.class))).thenReturn(List.of(slow, slowSibling, fast));
            when(enricher.enrich(any(Job.class))).thenAnswer(invocation -> {
                var candidate = invocation.getArgument(0, Job.class);
                if (candidate.companyWebsite().contains("slow.example")) {
                    Thread.sleep(300);
                    return candidate;
                }
                return candidate.withContactEmail("jobs@fast.example");
            });
            service = new CompanyEnrichmentService(jobRepository, enricher, 200, 2,
                    Duration.ofMillis(40), Duration.ofMillis(500));

            var result = service.enrichMissingEmails(3);

            assertEquals(3, result.checked());
            assertEquals(1, result.enriched());
            assertEquals(2, result.failed());
        }

        @Test
        @DisplayName("a slow failing domain should be bounded and not block the batch")
        void enrich_whenDeadHostExceedsBudget_shouldCountFailedAndContinue() {
            var dead = job(1L, "https://dead.example/");
            var good = job(2L, "https://good.example/");
            when(jobRepository.findJobsNeedingEnrichment(eq(2), any(LocalDate.class))).thenReturn(List.of(dead, good));
            when(enricher.enrich(any(Job.class))).thenAnswer(invocation -> {
                var candidate = invocation.getArgument(0, Job.class);
                if (candidate.companyWebsite().contains("dead.example")) {
                    Thread.sleep(300);
                    throw new IllegalStateException("host unavailable");
                }
                return candidate.withContactEmail("jobs@good.example");
            });
            service = new CompanyEnrichmentService(jobRepository, enricher, 200, 2,
                    Duration.ofMillis(40), Duration.ofMillis(500));

            var result = service.enrichMissingEmails(2);

            assertEquals(1, result.enriched());
            assertEquals(1, result.failed());
        }

        @Test
        @DisplayName("domains should run concurrently up to the configured limit")
        void enrich_whenConcurrentDomains_shouldRunTogether() throws InterruptedException {
            var jobs = List.of(
                    job(1L, "https://a.example/"),
                    job(2L, "https://b.example/"),
                    job(3L, "https://c.example/"),
                    job(4L, "https://d.example/"));
            var allStarted = new CountDownLatch(4);
            when(jobRepository.findJobsNeedingEnrichment(eq(4), any(LocalDate.class))).thenReturn(jobs);
            when(enricher.enrich(any(Job.class))).thenAnswer(invocation -> {
                var candidate = invocation.getArgument(0, Job.class);
                allStarted.countDown();
                assertTrue(allStarted.await(250, TimeUnit.MILLISECONDS),
                        "all domain crawls should start before any one finishes");
                return candidate.withContactEmail("jobs@" + candidate.companyWebsite().substring(8, 9) + ".example");
            });
            service = new CompanyEnrichmentService(jobRepository, enricher, 200, 4,
                    Duration.ofSeconds(1), Duration.ofSeconds(2));

            var result = service.enrichMissingEmails(4);

            assertEquals(4, result.enriched());
            assertEquals(0, result.failed());
        }

        @Test
        @DisplayName("the global budget should stop scheduling and report skipped domains")
        void enrich_whenGlobalBudgetExhausted_shouldStopSchedulingAndReportPartial() {
            var first = job(1L, "https://first.example/");
            var second = job(2L, "https://second.example/");
            var third = job(3L, "https://third.example/");
            when(jobRepository.findJobsNeedingEnrichment(eq(3), any(LocalDate.class))).thenReturn(List.of(first, second, third));
            when(enricher.enrich(any(Job.class))).thenAnswer(invocation -> {
                var candidate = invocation.getArgument(0, Job.class);
                Thread.sleep(100);
                return candidate.withContactEmail("jobs@first.example");
            });
            service = new CompanyEnrichmentService(jobRepository, enricher, 200, 1,
                    Duration.ofMillis(300), Duration.ofMillis(30));

            var result = service.enrichMissingEmails(3);

            assertEquals(3, result.checked());
            assertEquals(1, result.enriched());
            assertEquals(0, result.failed());
            assertEquals(2, result.domainsSkippedBudget());
            verify(enricher, times(1)).enrich(any(Job.class));
        }
    }

    @Nested
    @DisplayName("Phase 3: relevance window")
    class RelevanceWindowTests {

        @Test
        @DisplayName("enrichMissingEmails should query only jobs posted inside the configured window")
        void enrich_shouldPassRecentCutoffToRepository() {
            var clock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
            service = new CompanyEnrichmentService(jobRepository, enricher, 200, 4,
                    Duration.ofSeconds(15), Duration.ofSeconds(120), 30, clock);
            when(jobRepository.findJobsNeedingEnrichment(10, LocalDate.of(2026, 10, 20)))
                    .thenReturn(List.of());

            var result = service.enrichMissingEmails(10);

            assertEquals(0, result.checked());
            verify(jobRepository).findJobsNeedingEnrichment(10, LocalDate.of(2026, 10, 20));
            verify(enricher, never()).enrich(any(Job.class));
        }

        @Test
        @DisplayName("enrichMissingEmails should honor a custom window length")
        void enrich_whenCustomWindow_shouldShiftCutoff() {
            var clock = Clock.fixed(Instant.parse("2026-11-19T00:30:00Z"), ZoneOffset.UTC);
            service = new CompanyEnrichmentService(jobRepository, enricher, 200, 4,
                    Duration.ofSeconds(15), Duration.ofSeconds(120), 7, clock);
            when(jobRepository.findJobsNeedingEnrichment(20, LocalDate.of(2026, 11, 12)))
                    .thenReturn(List.of());

            service.enrichMissingEmails(20);

            verify(jobRepository).findJobsNeedingEnrichment(20, LocalDate.of(2026, 11, 12));
        }
    }
}
