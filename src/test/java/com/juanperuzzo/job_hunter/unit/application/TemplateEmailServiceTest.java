package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TemplateEmailServiceTest {

    private static final User USER = new User(1L, "juan@example.com", "Juan Antonio Peruzzo", "hash");

    private TemplateEmailService templateEmailService;

    @BeforeEach
    void setUp() {
        templateEmailService = new TemplateEmailService();
    }

    private static UserProfile fullProfile() {
        return new UserProfile(10L, 1L, "Resume text",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(
                        new Project("Job Hunter", "API com Spring Boot", "Spring Boot"),
                        new Project("LovLink", "SaaS comercial", "PostgreSQL")
                ),
                "(42) 99833-1363", "juan@example.com",
                "https://juanperuzzo.is-a.dev", "https://github.com/Uzzoper",
                "https://linkedin.com/in/juan", null);
    }

    @Test
    @DisplayName("generate should resolve job title, company and all personal placeholders without leaking raw tokens")
    void generate_whenFullProfile_shouldResolveAllPlaceholders() {
        var job = new Job(1L, "Desenvolvedor Java Júnior", "Acme Corp",
                "https://example.com/job", "Desc", LocalDate.now(), "source");

        var result = templateEmailService.generate(job, USER, fullProfile());

        assertAll(
                () -> assertTrue(result.subject().contains("Desenvolvedor Java Júnior")),
                () -> assertTrue(result.subject().contains("Acme Corp")),
                () -> assertTrue(result.body().contains("Desenvolvedor Java Júnior")),
                () -> assertTrue(result.body().contains("Acme Corp")),
                () -> assertTrue(result.body().contains("Juan Antonio Peruzzo")),
                () -> assertTrue(result.body().contains("(42) 99833-1363")),
                () -> assertTrue(result.body().contains("juan@example.com")),
                () -> assertTrue(result.body().contains("https://juanperuzzo.is-a.dev")),
                () -> assertTrue(result.body().contains("https://github.com/Uzzoper")),
                () -> assertTrue(result.body().contains("https://linkedin.com/in/juan")),
                () -> assertTrue(result.body().contains("Java, Spring Boot, PostgreSQL")),
                () -> assertTrue(result.body().contains("• Job Hunter — API com Spring Boot (Spring Boot)")),
                () -> assertFalse(result.subject().contains("{{")),
                () -> assertFalse(result.body().contains("{{")),
                () -> assertFalse(result.body().contains("}}"))
        );
    }

    @Test
    @DisplayName("generate should omit lines of missing optional profile fields without leaking raw tokens")
    void generate_whenOptionalFieldsMissing_shouldOmitTheirLines() {
        var job = new Job(1L, "Desenvolvedor Java Júnior", "Acme Corp",
                "https://example.com/job", "Desc", LocalDate.now(), "source");
        UserProfile profile = new UserProfile(10L, 1L, "Resume text",
                List.of("Java", "PostgreSQL"), CompanyTone.FORMAL, List.of(),
                null, null, null, " ", "https://linkedin.com/in/juan", null);

        var result = templateEmailService.generate(job, USER, profile);

        assertAll(
                () -> assertTrue(result.body().contains("Juan Antonio Peruzzo")),
                () -> assertTrue(result.body().contains("juan@example.com")),
                () -> assertTrue(result.body().contains("Java, PostgreSQL")),
                () -> assertTrue(result.body().contains("https://linkedin.com/in/juan")),
                () -> assertFalse(result.body().contains("(42) 99833-1363")),
                () -> assertFalse(result.body().contains("Portfólio:")),
                () -> assertFalse(result.body().contains("GitHub:")),
                () -> assertFalse(result.body().contains("{{PHONE}}")),
                () -> assertFalse(result.body().contains("{{GITHUB_URL}}")),
                () -> assertFalse(result.body().contains("{{PROJECTS}}")),
                () -> assertFalse(result.body().contains("{{")),
                () -> assertFalse(result.body().contains("}}"))
        );
    }

    @Test
    @DisplayName("generate should keep a single closing CTA without duplicating the demo offer sentence")
    void generate_shouldNotDuplicateClosingSentence() {
        var job = new Job(1L, "Desenvolvedor Java Júnior", "Acme Corp",
                "https://example.com/job", "Desc", LocalDate.now(), "source");

        var result = templateEmailService.generate(job, USER, fullProfile());

        assertAll(
                () -> assertTrue(result.body().contains(
                        "Além dos requisitos da vaga, trabalho também com Java, Spring Boot, PostgreSQL.")),
                () -> assertTrue(result.body().contains(
                        "Segue meu currículo em anexo. Podemos agendar uma conversa para eu mostrar esses projetos rodando?")),
                () -> assertFalse(result.body().contains(
                        "Posso demonstrar qualquer um desses projetos em funcionamento em uma conversa rápida."))
        );
    }
}