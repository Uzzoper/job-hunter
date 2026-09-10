package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.AnalysisNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Collectors;

public class EmailGenerationService implements GenerateEmailUseCase, GetEmailDraftUseCase {

    private static final Logger log = LoggerFactory.getLogger(EmailGenerationService.class);
    private static final int MAX_RESUME_CHARS = 1000;

    private final AiPort aiPort;
    private final EmailDraftRepository emailDraftRepository;
    private final UserProfileRepository userProfileRepository;
    private final JobRepository jobRepository;
    private final JobAnalysisRepository jobAnalysisRepository;
    private final TemplateEmailService templateEmailService;
    private final BotMemorySyncService botMemorySyncService;
    private final int minMatchScore;

    public EmailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository,
                                  UserProfileRepository userProfileRepository,
                                  JobRepository jobRepository, JobAnalysisRepository jobAnalysisRepository,
                                  TemplateEmailService templateEmailService,
                                  BotMemorySyncService botMemorySyncService,
                                  int minMatchScore) {
        this.aiPort = aiPort;
        this.emailDraftRepository = emailDraftRepository;
        this.userProfileRepository = userProfileRepository;
        this.jobRepository = jobRepository;
        this.jobAnalysisRepository = jobAnalysisRepository;
        this.templateEmailService = templateEmailService;
        this.botMemorySyncService = botMemorySyncService;
        this.minMatchScore = minMatchScore;
    }

    @Override
    public EmailDraft generate(Long userId, Long jobId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + jobId));
        JobAnalysis analysis = jobAnalysisRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new AnalysisNotFoundException(
                        "Job must be analyzed before generating an email draft"));

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new AiException("User profile not found for userId: " + userId));

        var existingSent = emailDraftRepository.findByJobIdAndUserId(job.id(), userId)
                .filter(draft -> draft.status() == EmailStatus.SENT);
        if (existingSent.isPresent()) {
            log.debug("Skipping generation, job {} already applied (SENT) for user {}", job.id(), userId);
            return existingSent.get();
        }

        if (job.contactEmail() != null) {
            var alreadySent = emailDraftRepository.findSentByJobIdAndRecipientEmail(job.id(), job.contactEmail());
            if (alreadySent.isPresent()) {
                log.debug("Skipping generation, already sent to {} for job {}", alreadySent.get().recipientEmail(), job.id());
                return alreadySent.get();
            }
        }

        if (analysis.matchScore() >= minMatchScore) {
            return generateFromTemplate(job, userId);
        }

        try {
            String prompt = buildPrompt(job, analysis, profile);
            String response = aiPort.complete(prompt);
            var existingId = emailDraftRepository.findByJobIdAndUserId(job.id(), userId)
                    .map(EmailDraft::id)
                    .orElse(null);
            EmailDraft draft = parseEmailDraft(existingId, job.id(), userId, response, job.contactEmail());
            EmailDraft saved = emailDraftRepository.save(draft);
            if (saved.status() == EmailStatus.REJECTED) {
                writeRefusalReasonBestEffort(userId, job.id(), response);
            }
            return saved;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to generate email: " + e.getMessage(), e);
        }
    }

    /**
     * Best-effort write-back of the AI refusal reason to the bot memory, so the bot
     * learns why a job was skipped. Never fails the rejection flow: any failure is
     * logged as a WARN and swallowed.
     */
    private void writeRefusalReasonBestEffort(Long userId, Long jobId, String aiResponse) {
        try {
            botMemorySyncService.writeMemoryEntry(userId, refusalReason(aiResponse));
        } catch (Exception e) {
            log.warn("Could not write refusal reason to bot memory for user {} job {}: {}",
                    userId, jobId, e.getMessage());
        }
    }

    /** Extracts the reason text after the {@code NO_APPLY:} prefix (falls back to the whole response). */
    private static String refusalReason(String aiResponse) {
        String response = aiResponse.trim();
        String prefix = "NO_APPLY:";
        if (response.startsWith(prefix)) {
            String reason = response.substring(prefix.length()).trim();
            return reason.isEmpty() ? response : reason;
        }
        return response;
    }

    private EmailDraft generateFromTemplate(Job job, Long userId) {
        var template = templateEmailService.generate(job);
        var existingId = emailDraftRepository.findByJobIdAndUserId(job.id(), userId)
                .map(EmailDraft::id)
                .orElse(null);
        String body = template.body().trim();
        if (body.startsWith("NO_APPLY:")) {
            // Template results carrying the refusal marker follow the same contract as the
            // AI path: never persist a sendable PENDING draft, and write the reason back
            // to bot memory best-effort so the bot learns why the job was skipped.
            var refused = new EmailDraft(existingId, job.id(), userId, template.subject(), body,
                    EmailStatus.REJECTED, LocalDateTime.now(), null, job.contactEmail());
            EmailDraft saved = emailDraftRepository.save(refused);
            writeRefusalReasonBestEffort(userId, job.id(), body);
            return saved;
        }
        var draft = new EmailDraft(existingId, job.id(), userId, template.subject(), body,
                EmailStatus.PENDING, LocalDateTime.now(), null, job.contactEmail());
        return emailDraftRepository.save(draft);
    }

    @Override
    public EmailDraft getEmailDraft(Long userId, Long jobId) {
        return emailDraftRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new JobNotFoundException("Email draft not found for job id: " + jobId));
    }

    private String buildPrompt(Job job, JobAnalysis analysis, UserProfile profile) {
        String resumeExcerpt = profile.resumeText().length() <= MAX_RESUME_CHARS
                ? profile.resumeText()
                : profile.resumeText().substring(0, MAX_RESUME_CHARS) + "...";

        String tone = analysis.companyTone().name().toLowerCase();
        String matchedSkills = String.join(", ", analysis.matchedSkills());
        String missingSkills = String.join(", ", analysis.missingSkills());

        String projectsText = profile.projects().isEmpty()
                ? "No projects available."
                : profile.projects().stream()
                        .map(p -> "- " + p.name() + ": " + p.description() + " (" + p.techStack() + ")")
                        .collect(Collectors.joining("\n"));

        String prompt = """
            You are an expert at writing job application emails for tech positions.

            Write an email following the rules below.

            REFERENCE EXAMPLE — use this style, length, and level of personalization as a guide:

            Subject: Candidatura — Desenvolvedor Java Júnior

            Olá. Tudo bem?

            Gostaria de me candidatar à vaga de Desenvolvedor Java Júnior.

            Sou desenvolvedor back-end focado no ecossistema Java/Spring, com projetos em produção construídos com Java, Spring Boot, APIs REST, Git e bancos de dados relacionais.

            Alguns destaques do meu portfólio:

            • Job Hunter — API desenvolvida com Spring Boot, Clean Architecture, TDD e integração com Inteligência Artificial.
            • LovLink (lovlink.com.br) — SaaS comercial em produção, banco de dados PostgreSQL, integração de pagamentos via Mercado Pago e arquitetura full stack moderna.
            • Jishuu (jishuu.vercel.app) — plataforma com autenticação OAuth 2.0 (Google), gerenciamento de usuários e persistência de dados utilizando PostgreSQL.

            Além dos requisitos da vaga, trabalho também com JavaScript, React, Node.js, Docker e testes automatizados. Posso demonstrar qualquer um desses projetos em funcionamento em uma conversa rápida.

            Segue meu currículo em anexo. Podemos agendar uma conversa para eu mostrar esses projetos rodando?

            Atenciosamente,

            Juan Antonio Peruzzo
            (42) 99833-1363
            Portfólio: https://juanperuzzo.is-a.dev
            GitHub: https://github.com/Uzzoper

            MANDATORY RULES:
            1. First line must be "Subject: " followed by the subject
            2. After a blank line, write the email body
            3. Write 3-5 paragraphs — be detailed, reference specific technologies and projects
            4. Mention 2-3 candidate projects (choose the most relevant for the job)
            5. Be specific to the company and the role
            6. Tone: %s
            7. Language: Brazilian Portuguese
            8. End with the exact signature block (name, phone, portfolio, GitHub)
            9. Include the phrase "Segue meu currículo em anexo" before the signature
            10. Positioning: write as a professional developer who delivers working software — never use trainee phrasing ("em formação", "aprendendo", "buscando oportunidade", "venho me especializando"); education appears at most once as plain fact, never as the opening; close with a confident call to action, never with "fico à disposição"
            11. If the vacancy clearly has no fit with the candidate (non-tech role, stack entirely outside the candidate's, or level far below), DO NOT write an email. Respond with exactly one line: NO_APPLY: [one-line reason in English]. No subject, no body, no signature

            Tone guide:
            - formal: respectful, "Prezados"
            - casual: natural, direct
            - startup: energetic, mention culture and impact

            Candidate profile:
            - Resume: %s
            - Skills: %s

            Available projects:
            %s

            Job: %s at %s
            Matched skills: %s
            Missing skills (address matter-of-factly if relevant — never apologize or promise to learn them): %s
            Summary: %s
            """.formatted(tone, resumeExcerpt, String.join(", ", profile.skills()),
                projectsText, job.title(), job.company(),
                matchedSkills, missingSkills, analysis.summary());

        // Preferences context (preferences-scoring spec). Empty when the profile
        // has no meaningful preference → prompt stays byte-identical to pre-feature.
        String preferencesBlock = PreferencesPromptFormatter.block(profile.preferences());
        if (!preferencesBlock.isEmpty()) {
            prompt += "\n\n" + preferencesBlock + """


                Rule 12. If the job clearly conflicts with an explicit preference above (excluded company, incompatible work model, or salary below the floor), do NOT write an email — respond with exactly one line: NO_APPLY: [one-line reason in English].""";
        }
        return prompt;
    }

    /**
     * Parses the raw AI response into an {@code EmailDraft}.
     * <p>
     * If the trimmed response starts with the refusal prefix {@code NO_APPLY:}
     * (case-sensitive), no subject/body parsing happens: the draft is persisted as
     * {@code REJECTED} with an empty subject and the full trimmed response as body,
     * keeping the refusal reason auditable and never producing a fake sendable subject.
     * Otherwise it follows the {@code Subject: } split logic and produces a {@code PENDING} draft.
     */
    private EmailDraft parseEmailDraft(Long id, Long jobId, Long userId, String aiResponse, String recipientEmail) {
        String response = aiResponse.trim();

        if (response.startsWith("NO_APPLY:")) {
            return new EmailDraft(id, jobId, userId, "", response, EmailStatus.REJECTED, LocalDateTime.now(), null, recipientEmail);
        }

        String subject;
        String body;

        int subjectEnd = response.indexOf('\n');
        if (subjectEnd > 0) {
            subject = response.substring(0, subjectEnd).trim();
            body = response.substring(subjectEnd).trim();
        } else {
            subject = response.trim();
            body = "";
        }

        if (!subject.startsWith("Subject: ")) {
            subject = "Subject: " + subject;
        }

        return new EmailDraft(id, jobId, userId, subject, body, EmailStatus.PENDING, LocalDateTime.now(), null, recipientEmail);
    }
}
