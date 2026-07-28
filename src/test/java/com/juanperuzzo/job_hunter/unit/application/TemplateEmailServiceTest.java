package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TemplateEmailServiceTest {

    private TemplateEmailService templateEmailService;

    @BeforeEach
    void setUp() {
        templateEmailService = new TemplateEmailService();
    }

    @Test
    @DisplayName("should generate template with job title and company in subject and body")
    void generate_shouldReplaceJobTitleAndCompany() {
        var job = new Job(1L, "Desenvolvedor Java Júnior", "Acme Corp",
                "https://example.com/job", "Desc", LocalDate.now(), "source");

        var result = templateEmailService.generate(job);

        assertAll(
                () -> assertTrue(result.subject().contains("Desenvolvedor Java Júnior")),
                () -> assertTrue(result.subject().contains("Acme Corp")),
                () -> assertTrue(result.body().contains("Desenvolvedor Java Júnior")),
                () -> assertTrue(result.body().contains("Acme Corp")),
                () -> assertTrue(result.body().contains("https://juanperuzzo.is-a.dev")),
                () -> assertTrue(result.body().contains("Juan Antonio Peruzzo"))
        );
    }
}
