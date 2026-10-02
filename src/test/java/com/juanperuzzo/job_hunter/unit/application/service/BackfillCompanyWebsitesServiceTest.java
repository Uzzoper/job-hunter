package com.juanperuzzo.job_hunter.unit.application.service;

import com.juanperuzzo.job_hunter.application.port.out.CompanyDomainResolverPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.BackfillCompanyWebsitesService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackfillCompanyWebsitesService tests")
class BackfillCompanyWebsitesServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private CompanyDomainResolverPort companyDomainResolver;

    private BackfillCompanyWebsitesService service;

    @BeforeEach
    void setUp() {
        service = new BackfillCompanyWebsitesService(jobRepository, companyDomainResolver);
    }

    private static Job job(Long id, String url) {
        return new Job(id, "Dev " + id, "Co" + id, url, "desc", LocalDate.now(), "gupy", null, null);
    }

    @Test
    @DisplayName("run with dryRun=true should report preview counts without persisting anything")
    void run_whenDryRun_shouldReportPreviewCountsWithoutPersisting() {
        var j1 = job(1L, "https://techco.gupy.io/jobs/1");
        var j2 = job(2L, "https://techco.gupy.io/jobs/2");
        var j3 = job(3L, "https://brand.gupy.io/jobs/3");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy")).thenReturn(List.of(j1, j2, j3));
        when(companyDomainResolver.resolveCompanyWebsites(List.of(j1.url(), j2.url(), j3.url()), 50))
                .thenReturn(Map.of(j1.url(), "https://www.techco.com.br",
                        j2.url(), "https://www.techco.com.br"));

        var result = service.run(true, 50);

        assertEquals(3, result.scanned());
        assertEquals(2, result.filled());
        assertEquals(1, result.stillNull());
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("run with dryRun=false should persist exactly the null→found transitions")
    void run_whenApply_shouldPersistOnlyNullToFoundTransitions() {
        var j1 = job(1L, "https://techco.gupy.io/jobs/1");
        var j2 = job(2L, "https://brand.gupy.io/jobs/2");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy")).thenReturn(List.of(j1, j2));
        when(companyDomainResolver.resolveCompanyWebsites(List.of(j1.url(), j2.url()), 50))
                .thenReturn(Map.of(j1.url(), "https://www.techco.com.br"));

        var result = service.run(false, 50);

        assertEquals(1, result.filled());
        assertEquals(1, result.stillNull());
        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository, times(1)).save(captor.capture());
        assertEquals("https://www.techco.com.br", captor.getValue().companyWebsite(),
                "only the resolved host's job is persisted, carrying the resolved website");
    }

    @Test
    @DisplayName("run on apply should never overwrite an existing website (only null candidates are saved)")
    void run_whenApply_shouldNeverSaveAnExistingWebsiteOverwrite() {
        var j1 = job(1L, "https://techco.gupy.io/jobs/1");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy")).thenReturn(List.of(j1));
        when(companyDomainResolver.resolveCompanyWebsites(anyList(), eq(50)))
                .thenReturn(Map.of(j1.url(), "https://www.techco.com.br"));

        service.run(false, 50);

        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository, times(1)).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("https://www.techco.com.br", saved.companyWebsite());
        assertEquals(j1.url(), saved.url(),
                "URL identity must be preserved by the null→found transition");
    }

    @Test
    @DisplayName("run again after an apply should scan only the remaining null-website rows")
    void run_whenRerun_shouldScanOnlyRemainingNullsAndStayStable() {
        var j1 = job(1L, "https://techco.gupy.io/jobs/1");
        var j2 = job(2L, "https://brand.gupy.io/jobs/2");
        // First run applies j1 (j2 host unresolved → still null in the DB on the rerun).
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy"))
                .thenReturn(List.of(j1, j2), List.of(j2));
        when(companyDomainResolver.resolveCompanyWebsites(anyList(), eq(50)))
                .thenReturn(Map.of(j1.url(), "https://www.techco.com.br"));

        var first = service.run(false, 50);
        var second = service.run(false, 50);

        assertEquals(2, first.scanned());
        assertEquals(1, first.filled());
        assertEquals(1, second.scanned(), "the filled job must not be rescanned");
        assertEquals(0, second.filled());
        assertEquals(1, second.stillNull());
        verify(jobRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("run should forward maxHosts to the resolver (chunk bounding)")
    void run_shouldForwardMaxHostsToResolver() {
        var j1 = job(1L, "https://techco.gupy.io/jobs/1");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy")).thenReturn(List.of(j1));
        when(companyDomainResolver.resolveCompanyWebsites(List.of(j1.url()), 10))
                .thenReturn(Map.of(j1.url(), "https://www.techco.com.br"));

        var result = service.run(true, 10);

        assertEquals(1, result.filled());
        verify(companyDomainResolver).resolveCompanyWebsites(List.of(j1.url()), 10);
    }

    @Test
    @DisplayName("run with no candidates should return zeros without calling the resolver")
    void run_whenNoCandidates_shouldReturnZerosWithoutCallingResolver() {
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy")).thenReturn(List.of());

        var result = service.run(false, 50);

        assertEquals(0, result.scanned());
        assertEquals(0, result.filled());
        assertEquals(0, result.stillNull());
        verifyNoInteractions(companyDomainResolver);
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("run with afterId should page past a dead head that would otherwise starve every round")
    void run_whenDeadHeadExists_shouldPagePastItWithAfterId() {
        // Production livelock: scanning from the start with maxHosts=1 only ever resolved
        // dead1, so stillNull never fell and hosts after the head were never examined.
        var afterHead = job(101L, "https://techco.gupy.io/jobs/101");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNullAfterId("gupy", 100L))
                .thenReturn(List.of(afterHead));
        when(companyDomainResolver.resolveCompanyWebsites(List.of(afterHead.url()), 1))
                .thenReturn(Map.of(afterHead.url(), "https://www.techco.com.br"));

        var result = service.run(false, 1, 100L);

        assertEquals(1, result.scanned());
        assertEquals(1, result.filled());
        assertEquals(0, result.stillNull());
        verify(jobRepository).findBySourceAndCompanyWebsiteIsNullAfterId("gupy", 100L);
        verify(jobRepository, never()).findBySourceAndCompanyWebsiteIsNull(anyString());
    }

    @Test
    @DisplayName("run with afterId should resolve every host of the requested range and persist only those")
    void run_whenAfterIdGiven_shouldResolveLaterHostsAndPersistThem() {
        var later1 = job(101L, "https://techco.gupy.io/jobs/101");
        var later2 = job(102L, "https://techco.gupy.io/jobs/102");
        var later3 = job(103L, "https://brand.gupy.io/jobs/103");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNullAfterId("gupy", 100L))
                .thenReturn(List.of(later1, later2, later3));
        when(companyDomainResolver.resolveCompanyWebsites(anyList(), eq(50)))
                .thenReturn(Map.of(later1.url(), "https://www.techco.com.br",
                        later2.url(), "https://www.techco.com.br"));

        var result = service.run(false, 50, 100L);

        assertEquals(3, result.scanned());
        assertEquals(2, result.filled());
        assertEquals(1, result.stillNull());
        verify(companyDomainResolver).resolveCompanyWebsites(
                List.of(later1.url(), later2.url(), later3.url()), 50);
        var captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository, times(2)).save(captor.capture());
        assertEquals(List.of(later1.url(), later2.url()),
                captor.getAllValues().stream().map(Job::url).toList());
    }

    @Test
    @DisplayName("run with a null afterId should scan from the start with the legacy unbounded query")
    void run_whenAfterIdNull_shouldScanFromStartWithLegacyQuery() {
        var j1 = job(1L, "https://techco.gupy.io/jobs/1");
        when(jobRepository.findBySourceAndCompanyWebsiteIsNull("gupy")).thenReturn(List.of(j1));
        when(companyDomainResolver.resolveCompanyWebsites(List.of(j1.url()), 50))
                .thenReturn(Map.of(j1.url(), "https://www.techco.com.br"));

        var result = service.run(true, 50, null);

        assertEquals(1, result.scanned());
        assertEquals(1, result.filled());
        verify(jobRepository).findBySourceAndCompanyWebsiteIsNull("gupy");
        verify(jobRepository, never()).findBySourceAndCompanyWebsiteIsNullAfterId(anyString(), anyLong());
    }

    @Test
    @DisplayName("run with afterId past the last row should report zeros without calling the resolver")
    void run_whenAfterIdBeyondLastRow_shouldReturnZerosWithoutCallingResolver() {
        when(jobRepository.findBySourceAndCompanyWebsiteIsNullAfterId("gupy", 9999L))
                .thenReturn(List.of());

        var result = service.run(false, 50, 9999L);

        assertEquals(0, result.scanned());
        assertEquals(0, result.filled());
        assertEquals(0, result.stillNull());
        verifyNoInteractions(companyDomainResolver);
        verify(jobRepository, never()).save(any());
    }
}