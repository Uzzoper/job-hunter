package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.Job;

public class TemplateEmailService {

    private static final String TEMPLATE_SUBJECT = "Candidatura — %s na %s";
    private static final String TEMPLATE_BODY = """
            Olá. Tudo bem?

            Gostaria de me candidatar à vaga de %s na %s.

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

    public TemplateEmailService() {
    }

    public TemplateResult generate(Job job) {
        var subject = TEMPLATE_SUBJECT.formatted(job.title(), job.company());
        var body = TEMPLATE_BODY.formatted(job.title(), job.company());
        return new TemplateResult(subject, body);
    }

    public record TemplateResult(String subject, String body) {
    }
}
