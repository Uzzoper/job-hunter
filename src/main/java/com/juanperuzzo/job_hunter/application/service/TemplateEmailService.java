package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

/**
 * Stateless builder for the standard application email used on high-match jobs
 * ({@code matchScore >= threshold} — see {@code EmailGenerationService}).
 *
 * <p>The subject and body templates carry {@code {{PLACEHOLDER}}} tokens
 * (profile-placeholders spec). Job tokens ({@code {{JOB_TITLE}}},
 * {@code {{COMPANY}}}) are substituted directly from the {@link Job}; candidate
 * identity and contact tokens are resolved via {@link ProfilePlaceholders} with
 * the global rule that a rendered email never leaks a raw {@code {{...}}}
 * token — unresolvable placeholders drop their whole line.</p>
 */
public class TemplateEmailService {

    static final String TEMPLATE_SUBJECT = "Candidatura — {{JOB_TITLE}} na {{COMPANY}}";

    static final String TEMPLATE_BODY = """
            Olá. Tudo bem?

            Gostaria de me candidatar à vaga de {{JOB_TITLE}} na {{COMPANY}}.

            Sou desenvolvedor back-end focado no ecossistema Java/Spring, com projetos em produção construídos com Java, Spring Boot, APIs REST, Git e bancos de dados relacionais.

            Além dos requisitos da vaga, trabalho também com {{SKILLS}}.

            Alguns destaques do meu portfólio:

            {{PROJECTS}}

            Segue meu currículo em anexo. Podemos agendar uma conversa para eu mostrar esses projetos rodando?

            Atenciosamente,

            {{CANDIDATE_NAME}}
            {{PHONE}}
            E-mail: {{CANDIDATE_EMAIL}}
            Portfólio: {{PORTFOLIO_URL}}
            GitHub: {{GITHUB_URL}}
            LinkedIn: {{LINKEDIN_URL}}
            """;

    public TemplateEmailService() {
    }

    public TemplateResult generate(Job job, User user, UserProfile profile) {
        var subject = resolveJobTokens(TEMPLATE_SUBJECT, job);
        var body = ProfilePlaceholders.resolve(resolveJobTokens(TEMPLATE_BODY, job), user, profile);
        return new TemplateResult(subject, body);
    }

    private static String resolveJobTokens(String template, Job job) {
        return template
                .replace("{{JOB_TITLE}}", String.valueOf(job.title()))
                .replace("{{COMPANY}}", String.valueOf(job.company()));
    }

    public record TemplateResult(String subject, String body) {
    }
}