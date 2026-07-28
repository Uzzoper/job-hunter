package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.AutoSendEligibilityPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.domain.model.EligibleDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class AutoSendEligibilityService implements AutoSendEligibilityPort {

    private static final Logger log = LoggerFactory.getLogger(AutoSendEligibilityService.class);

    private final EmailDraftRepository emailDraftRepository;
    private final JobRepository jobRepository;
    private final JobAnalysisRepository jobAnalysisRepository;
    private final boolean requireReview;
    private final int dailyCap;

    public AutoSendEligibilityService(
            EmailDraftRepository emailDraftRepository,
            JobRepository jobRepository,
            JobAnalysisRepository jobAnalysisRepository,
            boolean requireReview,
            int dailyCap) {
        this.emailDraftRepository = emailDraftRepository;
        this.jobRepository = jobRepository;
        this.jobAnalysisRepository = jobAnalysisRepository;
        this.requireReview = requireReview;
        this.dailyCap = dailyCap;
    }

    @Override
    public Optional<EligibleDraft> nextEligibleDraft() {
        var usersWithBuckets = collectUserBuckets();

        var allEligible = new ArrayList<EligibleDraft>();
        for (var bucket : usersWithBuckets) {
            for (var candidate : bucket) {
                var jobOpt = jobRepository.findById(candidate.jobId());
                if (jobOpt.isEmpty()) continue;

                var job = jobOpt.get();
                if (job.contactEmail() == null) continue;

                var analysisOpt = jobAnalysisRepository.findByJobIdAndUserId(candidate.jobId(), candidate.userId());
                if (analysisOpt.isEmpty()) continue;

                allEligible.add(new EligibleDraft(candidate, analysisOpt.get().matchScore(), job.company(), job.contactEmail(), job.title()));
            }
        }

        if (allEligible.isEmpty()) {
            log.debug("No eligible drafts found for auto-send");
            return Optional.empty();
        }

        allEligible.sort(Comparator
                .comparingInt((EligibleDraft e) -> e.matchScore()).reversed()
                .thenComparing(e -> e.draft().generatedAt()));

        var chosen = allEligible.getFirst();
        long sentToday = emailDraftRepository.countByUserIdAndStatusAndSentAtAfter(
                chosen.draft().userId(), EmailStatus.SENT, todayStart());

        if (sentToday >= dailyCap) {
            log.debug("Daily cap reached for user {}, skipping", chosen.draft().userId());
            return Optional.empty();
        }

        return Optional.of(chosen);
    }

    private List<List<EmailDraft>> collectUserBuckets() {
        var statuses = requireReview
                ? List.of(EmailStatus.APPROVED)
                : List.of(EmailStatus.PENDING);

        var allDrafts = emailDraftRepository.findAllByStatusIn(statuses);

        var userIds = allDrafts.stream()
                .map(EmailDraft::userId)
                .distinct()
                .sorted()
                .toList();

        return userIds.stream()
                .map(uid -> allDrafts.stream()
                        .filter(d -> d.userId().equals(uid))
                        .sorted(Comparator.comparing(EmailDraft::generatedAt))
                        .toList())
                .toList();
    }

    private LocalDateTime todayStart() {
        return LocalDateTime.now(Clock.systemUTC()).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }
}
