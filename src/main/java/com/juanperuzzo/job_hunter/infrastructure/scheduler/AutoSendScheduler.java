package com.juanperuzzo.job_hunter.infrastructure.scheduler;

import com.juanperuzzo.job_hunter.application.port.in.AutoSendEligibilityPort;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ThreadLocalRandom;

public class AutoSendScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoSendScheduler.class);

    private final AutoSendEligibilityPort eligibilityPort;
    private final TemplateEmailService templateEmailService;
    private final GenerateEmailUseCase generateEmailUseCase;
    private final SendEmailUseCase sendEmailUseCase;
    private final boolean enabled;
    private final int jitterSeconds;

    public AutoSendScheduler(
            AutoSendEligibilityPort eligibilityPort,
            TemplateEmailService templateEmailService,
            GenerateEmailUseCase generateEmailUseCase,
            SendEmailUseCase sendEmailUseCase,
            boolean enabled,
            int jitterSeconds) {
        this.eligibilityPort = eligibilityPort;
        this.templateEmailService = templateEmailService;
        this.generateEmailUseCase = generateEmailUseCase;
        this.sendEmailUseCase = sendEmailUseCase;
        this.enabled = enabled;
        this.jitterSeconds = jitterSeconds;
    }

    @Scheduled(fixedDelayString = "${auto-send.interval-seconds:120}000")
    public void tick() {
        if (!enabled) return;

        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(jitterSeconds * 1000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        eligibilityPort.nextEligibleDraft().ifPresentOrElse(eligible -> {
            var userId = eligible.draft().userId();
            var jobId = eligible.draft().jobId();

            try {
                if (eligible.matchScore() >= 60) {
                    templateEmailService.generate(eligible);
                } else {
                    generateEmailUseCase.generate(userId, jobId);
                }
                sendEmailUseCase.send(userId, jobId);
                log.info("Auto-sent email for job {} (score {})", jobId, eligible.matchScore());
            } catch (Exception e) {
                log.warn("Auto-send failed for job {}: {}", jobId, e.getMessage());
            }
        }, () -> log.debug("No eligible draft for auto-send"));
    }
}
