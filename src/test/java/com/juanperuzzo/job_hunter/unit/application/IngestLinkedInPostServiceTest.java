package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.ContactEmailExtractorPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.IngestLinkedInPostService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * RED tests for {@code docs/specs/linkedin-posts-ingest.md} scenarios 2-9
 * (application layer). The web layer pins the HTTP mapping in
 * {@code JobIngestControllerTest}; the 401 lives in {@code BotTokenFilterTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IngestLinkedInPostService tests")
class IngestLinkedInPostServiceTest {

    private static final String PERMALINK =
            "https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678";

    @Mock
    private ContactEmailExtractorPort emailExtractorPort;

    @Mock
    private AiPort aiPort;

    @Mock
    private JobRepository jobRepository;

    private IngestLinkedInPostService service;

    @BeforeEach
    void setUp() {
        service = new IngestLinkedInPostService(
                emailExtractorPort, aiPort, jobRepository,
                Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC));
    }

    private com.juanperuzzo.job_hunter.application.port.in.IngestPostCommand command(LocalDate postedAt) {
        return new com.juanperuzzo.job_hunter.application.port.in.IngestPostCommand(
                PERMALINK, "Estamos contratando desenvolvedor Ruby para time de dados!", "Acme TI", postedAt);
    }

    @Test
    @DisplayName("ingest should reject a non-permalink URL with IllegalArgumentException before any pipeline step")
    void ingest_whenPermalinkIsNotLinkedInFeed_shouldThrowIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.ingest(
                new com.juanperuzzo.job_hunter.application.port.in.IngestPostCommand(
                        "https://www.linkedin.com/posts/algo-aleatorio", "texto", "Autor", null)));
        verifyNoInteractions(aiPort, jobRepository);
    }

    @Test
    @DisplayName("ingest should reject a blank text with IllegalArgumentException before any pipeline step")
    void ingest_whenBlankText_shouldThrowIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.ingest(
                new com.juanperuzzo.job_hunter.application.port.in.IngestPostCommand(
                        "https://www.linkedin.com/feed/update/urn:li:activity:7123456789012345678", "   ", "Autor", null)));
        verifyNoInteractions(aiPort, jobRepository);
    }

    @Test
    @DisplayName("ingest should return NoContact without AI or repository touches when the text carries no e-mail")
    void ingest_whenTextHasNoEmail_shouldReturnNoContactWithoutCallingAi() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn(null);

        var result = service.ingest(command(LocalDate.of(2026, 11, 4)));

        assertInstanceOf(
                com.juanperuzzo.job_hunter.application.port.in.IngestResult.NoContact.class, result);
        verifyNoInteractions(aiPort, jobRepository);
    }

    @Test
    @DisplayName("ingest should return Duplicate without AI call when the permalink is already saved")
    void ingest_whenUrlAlreadySaved_shouldReturnDuplicateWithoutCallingAi() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("rh@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(true);

        var result = service.ingest(command(LocalDate.of(2026, 11, 4)));

        assertInstanceOf(
                com.juanperuzzo.job_hunter.application.port.in.IngestResult.Duplicate.class, result);
        verifyNoInteractions(aiPort);
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("ingest should persist the extractor's ranked-winner e-mail (multi-email rule lives in the extractor)")
    void ingest_whenExtractorReturnsRankedEmail_shouldPersistWinningEmail() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("vagas@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        when(aiPort.complete(anyString()))
                .thenReturn("{\"ehVaga\": true, \"title\": \"Dev Ruby\", \"company\": \"Acme TI\"}");
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(command(LocalDate.of(2026, 11, 4)));

        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertEquals("vagas@acme.com.br", captor.getValue().contactEmail());
    }

    @Test
    @DisplayName("ingest should return NotJob and save nothing when the AI classifies ehVaga=false")
    void ingest_whenAiReturnsNotJob_shouldReturnNotJobAndSaveNothing() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("rh@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        when(aiPort.complete(anyString()))
                .thenReturn("{\"ehVaga\": false, \"title\": \"\", \"company\": \"\"}");

        var result = service.ingest(command(LocalDate.of(2026, 11, 4)));

        assertInstanceOf(
                com.juanperuzzo.job_hunter.application.port.in.IngestResult.NotJob.class, result);
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("ingest should return InvalidExtraction and save nothing when the AI title or company is blank or over the cap")
    void ingest_whenAiOutputMissingTitleOrCompany_shouldReturnInvalidExtractionAndSaveNothing() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("rh@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        when(aiPort.complete(anyString()))
                .thenReturn("{\"ehVaga\": true, \"title\": \"\", \"company\": \"Acme TI\"}");

        var result = service.ingest(command(LocalDate.of(2026, 11, 4)));

        assertInstanceOf(
                com.juanperuzzo.job_hunter.application.port.in.IngestResult.InvalidExtraction.class, result);
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("ingest should rethrow AiException untouched and save nothing when the AI call fails")
    void ingest_whenAiFails_shouldThrowAiExceptionAndSaveNothing() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("rh@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        doThrow(new AiException("hermes gateway down")).when(aiPort).complete(anyString());

        assertThrows(AiException.class, () -> service.ingest(command(LocalDate.of(2026, 11, 4))));

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("ingest should flag emailSuspect when the winning e-mail comes from a free domain")
    void ingest_whenFreeEmailDomain_shouldSaveWithSuspectFlag() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("pablo@gmail.com");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        when(aiPort.complete(anyString()))
                .thenReturn("{\"ehVaga\": true, \"title\": \"Dev Ruby\", \"company\": \"Guru RH\"}");
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(command(LocalDate.of(2026, 11, 4)));

        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(true, captor.getValue().emailSuspect());
    }

    @Test
    @DisplayName("ingest should save a linkedin-post Job carrying the bot-provided postedAt")
    void ingest_whenValidPostWithBotPostedAt_shouldSaveLinkedinPostJob() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("rh@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        when(aiPort.complete(anyString()))
                .thenReturn("{\"ehVaga\": true, \"title\": \"Dev Ruby\", \"company\": \"Acme TI\"}");
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.ingest(command(LocalDate.of(2026, 11, 3)));

        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("linkedin-post", saved.source());
        assertEquals(PERMALINK, saved.url());
        assertEquals("Dev Ruby", saved.title());
        assertEquals("Acme TI", saved.company());
        assertEquals("rh@acme.com.br", saved.contactEmail());
        assertEquals(LocalDate.of(2026, 11, 3), saved.postedAt());
        assertInstanceOf(
                com.juanperuzzo.job_hunter.application.port.in.IngestResult.Saved.class, result);
    }

    @Test
    @DisplayName("ingest should fall back to the collection date when the bot does not provide postedAt")
    void ingest_whenPostedAtMissing_shouldFallbackToCollectionDate() {
        when(emailExtractorPort.extract(isNull(), anyString())).thenReturn("rh@acme.com.br");
        when(jobRepository.existsByUrl(PERMALINK)).thenReturn(false);
        when(aiPort.complete(anyString()))
                .thenReturn("{\"ehVaga\": true, \"title\": \"Dev Ruby\", \"company\": \"Acme TI\"}");
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        service.ingest(command(null));

        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(LocalDate.of(2026, 11, 4), captor.getValue().postedAt(),
                "fallback = collection date from the injected Clock");
    }
}
