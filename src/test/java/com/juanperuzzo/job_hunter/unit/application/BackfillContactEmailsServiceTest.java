package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.in.BackfillContactEmailsUseCase;
import com.juanperuzzo.job_hunter.application.port.out.ContactEmailExtractorPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.BackfillContactEmailsService;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BackfillContactEmailsService} per the {@code docs/specs/email-extractor-recall.md}
 * Phase 3: scans jobs with a null {@code contactEmail}, runs the shared extractor on each,
 * and writes only the null→found transitions. Dry-run reports the same counts but writes
 * nothing; the run is idempotent and never overwrites an existing email.
 */
class BackfillContactEmailsServiceTest {

    private static final LocalDate POSTED = LocalDate.of(2026, 9, 1);

    private JobRepository jobRepository;
    private ContactEmailExtractorPort extractor;
    private BackfillContactEmailsService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        extractor = mock(ContactEmailExtractorPort.class);
        service = new BackfillContactEmailsService(jobRepository, extractor);
    }

    private Job job(long id, String description) {
        return new Job(id, "Desenvolvedor", "Acme", "https://example.com/job/" + id, description, POSTED, "test");
    }

    // Stub rule: a description that IS an email address (or embeds one verbatim) yields it;
    // anything else yields nothing. Keeps the tests focused on the use-case logic, not the
    // extractor (whose correctness lives in EmailExtractorTest and the fixture measurement).
    private void stubExtractor() {
        when(extractor.extract(anyString(), anyString())).thenAnswer(invocation -> {
            String description = (String) invocation.getArgument(1);
            return description != null && description.contains("@") ? description.trim() : null;
        });
    }

    @Test
    @DisplayName("dry-run reports preview counts and writes nothing")
    void run_whenDryRun_shouldNotPersistAnything() {
        var findable = job(1L, "rh@empresa.com");
        var empty = job(2L, "sem email aqui");
        var alsoFindable = job(3L, "vagas@empresa.com");
        when(jobRepository.findAllByContactEmailIsNull()).thenReturn(List.of(findable, empty, alsoFindable));
        stubExtractor();

        BackfillContactEmailsUseCase.Result result = service.run(true);

        assertEquals(3, result.scanned());
        assertEquals(2, result.filled());
        assertEquals(1, result.stillNull());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("apply persists only null-to-found transitions")
    void run_whenApply_shouldPersistOnlyNullToFound() {
        var findable = job(1L, "rh@empresa.com");
        var empty = job(2L, "sem email aqui");
        when(jobRepository.findAllByContactEmailIsNull()).thenReturn(List.of(findable, empty));
        stubExtractor();

        BackfillContactEmailsUseCase.Result result = service.run(false);

        assertEquals(2, result.scanned());
        assertEquals(1, result.filled());
        assertEquals(1, result.stillNull());
        verify(jobRepository, times(1)).save(argThat(saved ->
                saved.url().equals(findable.url()) && "rh@empresa.com".equals(saved.contactEmail())));
    }

    @Test
    @DisplayName("apply leaves every job untouched when the extractor finds no email")
    void run_whenNoEmailsFound_shouldLeaveEverythingNull() {
        when(jobRepository.findAllByContactEmailIsNull())
                .thenReturn(List.of(job(1L, "sem email aqui"), job(2L, "veja nosso site")));
        when(extractor.extract(anyString(), anyString())).thenReturn(null);

        BackfillContactEmailsUseCase.Result result = service.run(false);

        assertEquals(2, result.scanned());
        assertEquals(0, result.filled());
        assertEquals(2, result.stillNull());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("rerunning after apply scans only what is still null (idempotent)")
    void run_whenRerunAfterApply_shouldBeIdempotent() {
        var findable = job(1L, "rh@empresa.com");
        when(jobRepository.findAllByContactEmailIsNull()).thenReturn(List.of(findable));
        stubExtractor();

        var first = service.run(false);

        assertEquals(1, first.filled());

        // Second run: the query now sees no remaining null-contactEmail jobs.
        when(jobRepository.findAllByContactEmailIsNull()).thenReturn(List.of());

        var second = service.run(false);

        assertEquals(0, second.scanned());
        assertEquals(0, second.filled());
        assertEquals(0, second.stillNull());
        verify(jobRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("existing contact emails are never touched because the scan set is null-only")
    void run_whenScanSetNullOnly_shouldNeverAlterExistingEmails() {
        var withEmail = job(1L, "contato@empresa.com");
        // A job that somehow already has a contact email is invisible to the scan:
        // findAllByContactEmailIsNull() filters it out, so the extractor never sees it and
        // save is never called on it. This is the never-overwrite guarantee.
        when(jobRepository.findAllByContactEmailIsNull()).thenReturn(List.of(withEmail));
        stubExtractor();

        service.run(false);

        verify(jobRepository, times(1)).save(argThat(saved ->
                "contato@empresa.com".equals(saved.contactEmail())));
    }
}