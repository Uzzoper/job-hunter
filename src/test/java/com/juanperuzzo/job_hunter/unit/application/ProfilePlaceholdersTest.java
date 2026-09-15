package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.service.ProfilePlaceholders;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePlaceholdersTest {

    private static final String TEMPLATE = """
            Nome: {{CANDIDATE_NAME}}
            E-mail: {{CANDIDATE_EMAIL}}
            Telefone: {{PHONE}}
            Portfólio: {{PORTFOLIO_URL}}
            GitHub: {{GITHUB_URL}}
            LinkedIn: {{LINKEDIN_URL}}
            Skills: {{SKILLS}}
            {{PROJECTS}}
            """;

    @Test
    @DisplayName("resolve should substitute every available placeholder and leave no raw token when the profile is complete")
    void resolve_whenFullProfile_shouldSubstituteAllPlaceholders() {
        User user = new User(1L, "juan@example.com", "Juan Antonio Peruzzo", "hash");
        UserProfile profile = new UserProfile(10L, 1L, "Resume text",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(
                        new Project("Job Hunter", "API com Spring Boot", "Spring Boot"),
                        new Project("LovLink", "SaaS comercial", "PostgreSQL")
                ),
                "(42) 99999-0000", "juan@example.com",
                "https://juanperuzzo.is-a.dev", "https://github.com/Uzzoper",
                "https://linkedin.com/in/juan", null);

        String resolved = ProfilePlaceholders.resolve(TEMPLATE, user, profile);

        assertAll(
                () -> assertTrue(resolved.contains("Juan Antonio Peruzzo")),
                () -> assertTrue(resolved.contains("juan@example.com")),
                () -> assertTrue(resolved.contains("(42) 99999-0000")),
                () -> assertTrue(resolved.contains("https://juanperuzzo.is-a.dev")),
                () -> assertTrue(resolved.contains("https://github.com/Uzzoper")),
                () -> assertTrue(resolved.contains("https://linkedin.com/in/juan")),
                () -> assertTrue(resolved.contains("Java, Spring Boot, PostgreSQL")),
                () -> assertTrue(resolved.contains("• Job Hunter — API com Spring Boot (Spring Boot)")),
                () -> assertTrue(resolved.contains("• LovLink — SaaS comercial (PostgreSQL)")),
                () -> assertFalse(resolved.contains("{{")),
                () -> assertFalse(resolved.contains("}}"))
        );
    }

    @Test
    @DisplayName("resolve should drop the whole line of each missing optional field and never leak a raw token")
    void resolve_whenOptionalFieldsMissing_shouldOmitTheirLines() {
        User user = new User(1L, "juan@example.com", "Juan Antonio Peruzzo", "hash");
        UserProfile profile = new UserProfile(10L, 1L, "Resume text",
                List.of("Java", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(),
                null, null, null, " ", "https://linkedin.com/in/juan", null);

        String resolved = ProfilePlaceholders.resolve(TEMPLATE, user, profile);

        assertAll(
                () -> assertTrue(resolved.contains("Juan Antonio Peruzzo")),
                () -> assertTrue(resolved.contains("juan@example.com")),
                () -> assertTrue(resolved.contains("Java, PostgreSQL")),
                () -> assertTrue(resolved.contains("https://linkedin.com/in/juan")),
                () -> assertFalse(resolved.contains("Telefone:")),
                () -> assertFalse(resolved.contains("Portfólio:")),
                () -> assertFalse(resolved.contains("GitHub:")),
                () -> assertFalse(resolved.contains("{{PHONE}}")),
                () -> assertFalse(resolved.contains("{{GITHUB_URL}}")),
                () -> assertFalse(resolved.contains("{{PORTFOLIO_URL}}")),
                () -> assertFalse(resolved.contains("{{PROJECTS}}")),
                () -> assertFalse(resolved.contains("{{")),
                () -> assertFalse(resolved.contains("}}"))
        );
    }
}