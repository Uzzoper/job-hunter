package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.EligibleDraft;

import java.time.LocalDateTime;

public class TemplateEmailService {

    private static final String TEMPLATE_SUBJECT = "Candidatura — %s";
    private static final String TEMPLATE_BODY = """
            Olá. Tudo bem?

            Gostaria de me candidatar à vaga de %s.

            Atualmente curso Engenharia de Software e venho me especializando em desenvolvimento back-end com Java. Tenho experiência prática com Java, Spring Boot, APIs REST, Git, bancos de dados relacionais e desenvolvimento de aplicações web.

            Alguns destaques do meu portfólio:

            • Job Hunter — API desenvolvida com Spring Boot, Clean Architecture, TDD e integração com Inteligência Artificial.
            • LovLink (lovlink.com.br) — SaaS comercial em produção, banco de dados PostgreSQL, integração de pagamentos via Mercado Pago e arquitetura full stack moderna.
            • Jishuu (jishuu.vercel.app) — plataforma com autenticação OAuth 2.0 (Google), gerenciamento de usuários e persistência de dados utilizando PostgreSQL.

            Além dos requisitos da vaga, possuo conhecimentos em JavaScript, React, Node.js, Docker, testes automatizados e versionamento com Git. Estou sempre buscando aprimorar minhas habilidades e aprender novas tecnologias para contribuir cada vez mais com o time e com os projetos em que atuo.

            Segue meu currículo em anexo. Fico à disposição para uma conversa.

            Atenciosamente,

            Juan Antonio Peruzzo
            (42) 99833-1363
            Portfólio: https://juanperuzzo.is-a.dev
            GitHub: https://github.com/Uzzoper
            """;

    private final EmailDraftRepository emailDraftRepository;

    public TemplateEmailService(EmailDraftRepository emailDraftRepository) {
        this.emailDraftRepository = emailDraftRepository;
    }

    public EmailDraft generate(EligibleDraft eligible) {
        var jobTitle = eligible.jobTitle();

        var subject = TEMPLATE_SUBJECT.formatted(jobTitle);
        var body = TEMPLATE_BODY.formatted(jobTitle);

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
