package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.domain.model.EligibleDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateEmailServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long JOB_ID = 2L;
    private static final Long DRAFT_ID = 10L;
    private static final String JOB_TITLE = "Desenvolvedor Java Júnior";

    @Mock
    private EmailDraftRepository emailDraftRepository;

    @InjectMocks
    private TemplateEmailService templateEmailService;

    @Test
    @DisplayName("should generate template email with job title in subject and body")
    void generate_shouldReplaceJobTitle() {
        var draft = new EmailDraft(DRAFT_ID, JOB_ID, USER_ID, "Subject placeholder", "Body placeholder", EmailStatus.PENDING, LocalDateTime.now(), null);
        var eligible = new EligibleDraft(draft, 75, "Acme Corp", "hr@acme.com", JOB_TITLE);

        when(emailDraftRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = templateEmailService.generate(eligible);

        assertAll(
                () -> assertTrue(result.subject().contains(JOB_TITLE)),
                () -> assertTrue(result.body().contains(JOB_TITLE)),
                () -> assertTrue(result.body().contains("https://juanperuzzo.is-a.dev")),
                () -> assertTrue(result.body().contains("Juan Antonio Peruzzo")),
                () -> assertEquals(EmailStatus.PENDING, result.status()),
                () -> assertEquals(DRAFT_ID, result.id())
        );
        verify(emailDraftRepository).save(any());
    }
}
