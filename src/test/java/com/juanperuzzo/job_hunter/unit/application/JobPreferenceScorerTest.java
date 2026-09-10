package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.service.JobPreferenceScorer;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the deterministic preferences→scoring modifier
 * (doc: docs/specs/preferences-scoring.md).
 */
@DisplayName("JobPreferenceScorer tests")
class JobPreferenceScorerTest {

    private static final LocalDate TODAY = LocalDate.now();

    private Job job(String description, String company) {
        return new Job(1L, "Developer", company, "https://example.com/job/1",
                description, TODAY, "test");
    }

    // ---------- Remote preference ----------

    @Test
    @DisplayName("adjust should subtract 15 when a remote-preferring user sees an onsite ad")
    void remote_whenDescriptionOnsite_shouldSubtractFullPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Trabalho 100% presencial em São Paulo.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should subtract 8 when a remote-preferring user sees a hybrid ad")
    void remote_whenDescriptionHybrid_shouldSubtractMildPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Modelo híbrido, 2 dias de home office por semana.", "CompanyX");

        assertEquals(72, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should not penalize a remote ad for a remote-preferring user")
    void remote_whenDescriptionRemote_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Vaga remota para qualquer lugar do Brasil.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should still detect onsite when the ad negates remote ('não é remoto')")
    void remote_whenDescriptionNegatesRemote_shouldStillDetectOnsite() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Atuação 100% presencial — não é remoto e não é home office.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should not penalize a silent ad (no work-model signal)")
    void remote_whenDescriptionSilent_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Java com experiência em Spring Boot.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    // ---------- Hybrid preference ----------

    @Test
    @DisplayName("adjust should subtract 8 for hybrid preference when the ad is remote and no preferred city is mentioned")
    void hybrid_whenDescriptionRemoteAndCityUnknown_shouldSubtractMildPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
        Job job = job("Vaga 100% remota.", "CompanyX");

        assertEquals(72, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should not penalize when the ad mentions a preferred city")
    void hybrid_whenDescriptionMentionsPreferredCity_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
        Job job = job("Trabalho híbrido com escritório em Curitiba.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should not penalize a silent ad for a hybrid-preferring user")
    void hybrid_whenDescriptionSilent_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
        Job job = job("Oportunidade na área de TI.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    // ---------- Onsite preference ----------

    @Test
    @DisplayName("adjust should subtract 15 for onsite preference when the ad is remote and no preferred city is mentioned")
    void onsite_whenDescriptionRemoteAndCityMissing_shouldSubtractFullPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Onsite(List.of("Curitiba")), null, List.of());
        Job job = job("Vaga totalmente remota para todo o Brasil.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should subtract 8 for onsite preference when the remote ad still mentions a preferred city")
    void onsite_whenDescriptionRemoteButCityMatch_shouldSubtractMildPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Onsite(List.of("São Paulo")), null, List.of());
        Job job = job("Atuação remota, com escritório disponível em São Paulo.", "CompanyX");

        assertEquals(72, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should not penalize an onsite ad in a preferred city for an onsite-preferring user")
    void onsite_whenDescriptionOnsiteInPreferredCity_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Onsite(List.of("São Paulo")), null, List.of());
        Job job = job("Vaga presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    // ---------- Excluded companies ----------

    @Test
    @DisplayName("adjust should cap the score at 15 when the company is excluded (case-insensitive)")
    void excludedCompany_whenCompanyMatches_shouldCapScore() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("Desenvolvedor Java.", "ACME CORP");

        assertEquals(15, JobPreferenceScorer.adjust(90, job, prefs));
    }

    @Test
    @DisplayName("adjust should not cap when the company does not match any excluded entry")
    void excludedCompany_whenCompanyDoesNotMatch_shouldKeepScore() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("Desenvolvedor Java.", "Globex");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    @Test
    @DisplayName("adjust should keep the raw score when it is already below the cap")
    void excludedCompany_whenCoreScoreBelowCap_shouldKeepRawScore() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("Desenvolvedor Java.", "Acme Corp");

        assertEquals(10, JobPreferenceScorer.adjust(10, job, prefs));
    }

    // ---------- Identity guarantee ----------

    @Test
    @DisplayName("adjust should return the raw score when preferences are null")
    void adjust_whenNoPreferences_shouldReturnRawScore() {
        Job job = job("Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, null));
    }

    @Test
    @DisplayName("adjust should return the raw score for a semantically blank preferences object")
    void adjust_whenEmptyPreferences_shouldReturnRawScore() {
        Job job = job("Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, UserPreferences.empty()));
    }

    @Test
    @DisplayName("adjust should not change the score when only salaryFloor is set (prompt-only signal)")
    void adjust_whenOnlySalaryFloor_shouldNotChangeScore() {
        UserPreferences prefs = new UserPreferences(null, 5000, List.of());
        Job job = job("Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs));
    }

    // ---------- Clamping ----------

    @Test
    @DisplayName("adjust should clamp at both bounds after applying the modifier")
    void adjust_whenScoreWouldExceedBounds_shouldClamp() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job onsiteJob = job("Trabalho 100% presencial.", "CompanyX");

        assertEquals(0, JobPreferenceScorer.adjust(5, onsiteJob, prefs));
        assertEquals(85, JobPreferenceScorer.adjust(100, onsiteJob, prefs));
    }
}