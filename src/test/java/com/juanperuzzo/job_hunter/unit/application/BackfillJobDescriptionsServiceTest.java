package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.JobDescriptionSourcePort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.BackfillJobDescriptionsService;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillJobDescriptionsService")
class BackfillJobDescriptionsServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobDescriptionSourcePort descriptionSource;

    private BackfillJobDescriptionsService service;

    private static final String LONG_TEXT = "Descrição completa com stack, requisitos e benefícios. ".repeat(10);

    @BeforeEach
    void setUp() {
        service = new BackfillJobDescriptionsService(jobRepository, descriptionSource);
    }

    private static Job job(long id, String description) {
        return new Job(id, "Dev Java", "Acme", "https://www.infojobs.com.br/vaga-de-dev__" + id + ".aspx",
                description, LocalDate.of(2026, 11, 1), "infojobs");
    }

    private void givenCandidates(Job... jobs) {
        when(jobRepository.findBySourceWithShortDescription(anyString(), anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of(jobs));
    }

    @Test
    @DisplayName("should count the updates of a dry run without saving anything")
    void run_whenDryRun_shouldReportUpdatesWithoutSaving() {
        givenCandidates(job(1L, "curta"));
        when(descriptionSource.fetchDescription(anyString())).thenReturn(Optional.of(LONG_TEXT));

        var result = service.run(true, 100, 300, null);

        assertEquals(1, result.scanned());
        assertEquals(1, result.updated());
        assertEquals(0, result.stillShort());
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("should persist the repaired description, preserving every other field")
    void run_whenSourceReturnsLongerText_shouldSaveWithNewDescription() {
        givenCandidates(job(7L, "curta"));
        when(descriptionSource.fetchDescription(anyString())).thenReturn(Optional.of(LONG_TEXT));

        var result = service.run(false, 100, 300, null);

        assertEquals(1, result.updated());
        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        var saved = captor.getValue();
        assertEquals(7L, saved.id());
        assertEquals(LONG_TEXT, saved.description());
        assertEquals("Dev Java", saved.title());
        assertEquals("Acme", saved.company());
        assertEquals("infojobs", saved.source());
        assertEquals("https://www.infojobs.com.br/vaga-de-dev__7.aspx", saved.url());
    }

    @Test
    @DisplayName("should keep the current description when the source returns something shorter")
    void run_whenSourceReturnsShorterText_shouldKeepCurrentDescription() {
        givenCandidates(job(1L, "descrição já boa e longa o suficiente"));
        when(descriptionSource.fetchDescription(anyString())).thenReturn(Optional.of("curta"));

        var result = service.run(false, 100, 300, null);

        assertEquals(0, result.updated());
        assertEquals(1, result.stillShort());
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("should treat a missing description as improvable")
    void run_whenCurrentDescriptionIsNull_shouldSaveTheFetchedText() {
        givenCandidates(job(1L, null));
        when(descriptionSource.fetchDescription(anyString())).thenReturn(Optional.of(LONG_TEXT));

        var result = service.run(false, 100, 300, null);

        assertEquals(1, result.updated());
        verify(jobRepository).save(any(Job.class));
    }

    @Test
    @DisplayName("should count an empty fetch as still short and continue with the rest")
    void run_whenSourceReturnsEmpty_shouldCountStillShortAndContinue() {
        givenCandidates(job(1L, "curta"), job(2L, "curta"));
        when(descriptionSource.fetchDescription("https://www.infojobs.com.br/vaga-de-dev__1.aspx"))
                .thenReturn(Optional.empty());
        when(descriptionSource.fetchDescription("https://www.infojobs.com.br/vaga-de-dev__2.aspx"))
                .thenReturn(Optional.of(LONG_TEXT));

        var result = service.run(true, 100, 300, null);

        assertEquals(2, result.scanned());
        assertEquals(1, result.updated());
        assertEquals(1, result.stillShort());
    }

    @Test
    @DisplayName("should survive a source failure and keep processing the batch")
    void run_whenSourceThrows_shouldContinueWithTheRest() {
        givenCandidates(job(1L, "curta"), job(2L, "curta"));
        when(descriptionSource.fetchDescription("https://www.infojobs.com.br/vaga-de-dev__1.aspx"))
                .thenThrow(new IllegalStateException("boom"));
        when(descriptionSource.fetchDescription("https://www.infojobs.com.br/vaga-de-dev__2.aspx"))
                .thenReturn(Optional.of(LONG_TEXT));

        var result = service.run(true, 100, 300, null);

        assertEquals(2, result.scanned());
        assertEquals(1, result.updated());
        assertEquals(1, result.stillShort());
    }

    @Test
    @DisplayName("should forward limit, max description length and source to the scan query")
    void run_whenCalled_shouldForwardTheScanParameters() {
        givenCandidates(job(1L, "curta"));

        service.run(true, 40, 500, 12L);

        verify(jobRepository).findBySourceWithShortDescription("infojobs", 500, 12L, 40);
    }

    @Test
    @DisplayName("should scan from the start when afterId is absent")
    void run_whenAfterIdMissing_shouldScanFromZero() {
        givenCandidates(job(1L, "curta"));

        var result = service.run(true, 100, 300, null);

        verify(jobRepository).findBySourceWithShortDescription("infojobs", 300, 0L, 100);
        assertEquals(1L, result.lastId());
    }

    @Test
    @DisplayName("should clamp a limit above the documented maximum")
    void run_whenLimitAboveMaximum_shouldClampTo200() {
        givenCandidates(job(1L, "curta"));

        service.run(true, 9_999, 300, null);

        verify(jobRepository).findBySourceWithShortDescription(eq("infojobs"), eq(300), eq(0L), eq(200));
    }

    @Test
    @DisplayName("should return zeroes when there is nothing to repair")
    void run_whenNoCandidates_shouldReturnZeroes() {
        givenCandidates();

        var result = service.run(true, 100, 300, null);

        assertEquals(0, result.scanned());
        assertEquals(0, result.updated());
        assertEquals(0, result.stillShort());
        assertEquals(0L, result.lastId());
        verify(descriptionSource, never()).fetchDescription(anyString());
    }

    @Test
    @DisplayName("should scan no candidates when limit is zero or negative")
    void run_whenLimitZero_shouldReturnZeroesWithoutQuerying() {
        var result = service.run(true, 0, 300, null);

        assertEquals(0, result.scanned());
        assertEquals(0, result.updated());
        assertEquals(0, result.stillShort());
        assertEquals(0L, result.lastId());
        verify(jobRepository, never()).findBySourceWithShortDescription(anyString(), anyInt(), anyLong(), anyInt());
    }
}
