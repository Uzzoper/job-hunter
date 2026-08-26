package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.Job;

public class TemplateEmailService {

    private static final String TEMPLATE_SUBJECT = "Candidatura — %s na %s";
    private static final String TEMPLATE_BODY = """
            Olá. Tudo bem?

            Gostaria de me candidatar à vaga de %s na %s.

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
