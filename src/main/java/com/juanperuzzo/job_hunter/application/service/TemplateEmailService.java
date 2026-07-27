package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.EligibleDraft;

import java.time.LocalDateTime;

public class TemplateEmailService {

    private static final String TEMPLATE_SUBJECT = "Application for %s position";
    private static final String TEMPLATE_BODY = """
            Dear Hiring Team at %s,

            I am writing to express my strong interest in the %s position at %s.

            I believe my skills and experience make me a great fit for this role, and I would welcome the opportunity to discuss how I can contribute to your team.

            Thank you for your time and consideration.

            Best regards,
            [Your Name]
            """;

    private final EmailDraftRepository emailDraftRepository;

    public TemplateEmailService(EmailDraftRepository emailDraftRepository) {
        this.emailDraftRepository = emailDraftRepository;
    }

    public EmailDraft generate(EligibleDraft eligible) {
        var jobTitle = eligible.draft().subject();
        var company = eligible.company();

        var subject = TEMPLATE_SUBJECT.formatted(company);
        var body = TEMPLATE_BODY.formatted(company, jobTitle, company);

        var templateDraft = new EmailDraft(
                eligible.draft().id(),
                eligible.draft().jobId(),
                eligible.draft().userId(),
                subject,
                body,
                EmailStatus.PENDING,
                LocalDateTime.now(),
                null
        );

        return emailDraftRepository.save(templateDraft);
    }
}
