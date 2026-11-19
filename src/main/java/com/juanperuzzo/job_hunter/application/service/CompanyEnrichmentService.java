package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.CompanyEnrichmentUseCase;
import com.juanperuzzo.job_hunter.application.port.in.EnrichmentResult;
import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.PortalDomains;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enriches jobs with company-site contact emails as an explicit, out-of-band batch.
 * Only jobs posted inside the relevance window are candidates, newest first
 * (async-company-enrichment Phase 3), so the stale tail cannot starve applicable
 * listings. Domains are crawled once each, with bounded concurrency and per-domain
 * and global time budgets. Results are persisted in place so jobs do not get duplicated.
 */
public class CompanyEnrichmentService implements CompanyEnrichmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CompanyEnrichmentService.class);

    private static final int DEFAULT_MAX_LIMIT = 200;
    private static final int DEFAULT_DOMAIN_CONCURRENCY = 4;
    private static final Duration DEFAULT_DOMAIN_BUDGET = Duration.ofSeconds(15);
    private static final Duration DEFAULT_BATCH_BUDGET = Duration.ofSeconds(120);
    private static final int DEFAULT_MAX_JOB_AGE_DAYS = 30;

    private final JobRepository jobRepository;
    private final CompanySiteEnrichmentPort enricher;
    private final int maxLimit;
    private final int domainConcurrency;
    private final Duration domainBudget;
    private final Duration batchBudget;
    private final int maxJobAgeDays;
    private final Clock clock;

    public CompanyEnrichmentService(JobRepository jobRepository, CompanySiteEnrichmentPort enricher) {
        this(jobRepository, enricher, DEFAULT_MAX_LIMIT, DEFAULT_DOMAIN_CONCURRENCY,
                DEFAULT_DOMAIN_BUDGET, DEFAULT_BATCH_BUDGET);
    }

    public CompanyEnrichmentService(JobRepository jobRepository, CompanySiteEnrichmentPort enricher, int maxLimit) {
        this(jobRepository, enricher, maxLimit, DEFAULT_DOMAIN_CONCURRENCY,
                DEFAULT_DOMAIN_BUDGET, DEFAULT_BATCH_BUDGET);
    }

    public CompanyEnrichmentService(
            JobRepository jobRepository,
            CompanySiteEnrichmentPort enricher,
            int maxLimit,
            int domainConcurrency,
            Duration domainBudget,
            Duration batchBudget) {
        this(jobRepository, enricher, maxLimit, domainConcurrency, domainBudget, batchBudget,
                DEFAULT_MAX_JOB_AGE_DAYS, Clock.systemDefaultZone());
    }

    public CompanyEnrichmentService(
            JobRepository jobRepository,
            CompanySiteEnrichmentPort enricher,
            int maxLimit,
            int domainConcurrency,
            Duration domainBudget,
            Duration batchBudget,
            int maxJobAgeDays,
            Clock clock) {
        this.jobRepository = jobRepository;
        this.enricher = enricher;
        this.maxLimit = maxLimit > 0 ? maxLimit : DEFAULT_MAX_LIMIT;
        this.domainConcurrency = domainConcurrency > 0 ? domainConcurrency : DEFAULT_DOMAIN_CONCURRENCY;
        this.domainBudget = positiveOrDefault(domainBudget, DEFAULT_DOMAIN_BUDGET);
        this.batchBudget = positiveOrDefault(batchBudget, DEFAULT_BATCH_BUDGET);
        this.maxJobAgeDays = maxJobAgeDays > 0 ? maxJobAgeDays : DEFAULT_MAX_JOB_AGE_DAYS;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    @Override
    public EnrichmentResult enrichMissingEmails(int limit) {
        int clamped = Math.max(1, Math.min(limit, maxLimit));
        var postedSince = LocalDate.now(clock).minusDays(maxJobAgeDays);
        log.debug("company-site enrichment window: posted since {}", postedSince);
        List<Job> candidates;
        try {
            candidates = jobRepository.findJobsNeedingEnrichment(clamped, postedSince);
        } catch (Exception e) {
            log.error("failed to load jobs needing enrichment: {}", e.getMessage());
            return new EnrichmentResult(0, 0, 0, 0, 0);
        }

        int checked = candidates.size();
        int skippedPortal = 0;
        var byDomain = new LinkedHashMap<String, List<Job>>();
        for (var job : candidates) {
            var host = host(job.companyWebsite());
            if (host == null) {
                continue;
            }
            if (PortalDomains.isPortal(host)) {
                skippedPortal++;
                continue;
            }
            byDomain.computeIfAbsent(host, ignored -> new ArrayList<>()).add(job);
        }

        if (byDomain.isEmpty()) {
            return new EnrichmentResult(checked, 0, skippedPortal, 0, 0);
        }

        var domains = byDomain.entrySet().stream()
                .map(entry -> new DomainGroup(entry.getKey(), entry.getValue()))
                .toList();
        var threadNumber = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(domainConcurrency, task -> {
            var thread = new Thread(task, "company-enrichment-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        var completion = new ExecutorCompletionService<DomainOutcome>(executor);
        var active = new LinkedHashMap<Future<DomainOutcome>, PendingDomain>();
        var batchDeadline = System.nanoTime() + batchBudget.toNanos();
        int nextDomain = 0;
        int enriched = 0;
        int failed = 0;
        int domainsSkippedBudget = 0;

        try {
            while (nextDomain < domains.size() || !active.isEmpty()) {
                while (nextDomain < domains.size()
                        && active.size() < domainConcurrency
                        && System.nanoTime() < batchDeadline) {
                    var group = domains.get(nextDomain++);
                    var startedAt = new AtomicLong();
                    var future = completion.submit(() -> crawl(group, startedAt));
                    active.put(future, new PendingDomain(group, startedAt));
                }

                if (nextDomain < domains.size() && System.nanoTime() >= batchDeadline) {
                    domainsSkippedBudget = domains.size() - nextDomain;
                    nextDomain = domains.size();
                }

                if (active.isEmpty()) {
                    continue;
                }

                var completed = completion.poll(waitNanos(active, batchDeadline, nextDomain < domains.size()),
                        TimeUnit.NANOSECONDS);
                if (completed != null) {
                    var pending = active.remove(completed);
                    if (pending != null) {
                        try {
                            var outcome = completed.get();
                            if (outcome.failure() != null) {
                                failed += pending.group().jobs().size();
                                log.warn("company-site enrichment failed for domain {}: {}",
                                        pending.group().domain(), outcome.failure().getMessage());
                            } else if (outcome.email() != null) {
                                for (var job : pending.group().jobs()) {
                                    try {
                                        jobRepository.save(job.withContactEmail(outcome.email()));
                                        enriched++;
                                    } catch (Exception e) {
                                        log.warn("failed to save enrichment for job {}: {}", job.url(), e.getMessage());
                                        failed++;
                                    }
                                }
                            }
                        } catch (ExecutionException | CancellationException e) {
                            failed += pending.group().jobs().size();
                            log.warn("company-site enrichment task failed for domain {}: {}",
                                    pending.group().domain(), e.getMessage());
                        }
                    }
                }

                failed += expireDomains(active);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed += cancelActive(active);
        } catch (Exception e) {
            log.error("company-site enrichment batch failed: {}", e.getMessage());
            failed += cancelActive(active);
        } finally {
            executor.shutdownNow();
        }

        return new EnrichmentResult(checked, enriched, skippedPortal, failed, domainsSkippedBudget);
    }

    private DomainOutcome crawl(DomainGroup group, AtomicLong startedAt) {
        startedAt.set(System.nanoTime());
        try {
            var representative = group.jobs().get(0);
            var enrichedJob = enricher.enrich(representative);
            var email = enrichedJob == null ? null : enrichedJob.contactEmail();
            return new DomainOutcome(email, null);
        } catch (Exception e) {
            return new DomainOutcome(null, e);
        }
    }

    private int expireDomains(Map<Future<DomainOutcome>, PendingDomain> active) {
        int failed = 0;
        var now = System.nanoTime();
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var startedAt = entry.getValue().startedAt().get();
            if (entry.getKey().isDone() || startedAt == 0 || now - startedAt < domainBudget.toNanos()) {
                continue;
            }
            entry.getKey().cancel(true);
            failed += entry.getValue().group().jobs().size();
            log.warn("company-site enrichment timed out for domain {} after {} ms",
                    entry.getValue().group().domain(), domainBudget.toMillis());
            iterator.remove();
        }
        return failed;
    }

    private int cancelActive(Map<Future<DomainOutcome>, PendingDomain> active) {
        int failed = 0;
        for (var entry : active.entrySet()) {
            entry.getKey().cancel(true);
            failed += entry.getValue().group().jobs().size();
        }
        active.clear();
        return failed;
    }

    private long waitNanos(
            Map<Future<DomainOutcome>, PendingDomain> active,
            long batchDeadline,
            boolean moreDomainsToSchedule) {
        var now = System.nanoTime();
        var waitNanos = moreDomainsToSchedule ? Math.max(1, batchDeadline - now) : Long.MAX_VALUE;
        for (var pending : active.values()) {
            var startedAt = pending.startedAt().get();
            var untilTimeout = startedAt == 0
                    ? TimeUnit.MILLISECONDS.toNanos(5)
                    : startedAt + domainBudget.toNanos() - now;
            waitNanos = Math.min(waitNanos, Math.max(1, untilTimeout));
        }
        return waitNanos == Long.MAX_VALUE ? TimeUnit.MILLISECONDS.toNanos(5) : waitNanos;
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String host(String url) {
        try {
            var host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private record DomainGroup(String domain, List<Job> jobs) {}

    private record DomainOutcome(String email, Exception failure) {}

    private record PendingDomain(DomainGroup group, AtomicLong startedAt) {}
}
