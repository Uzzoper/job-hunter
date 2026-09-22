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

    private Job job(String title, String description, String company) {
        return new Job(1L, title, company, "https://example.com/job/1",
                description, TODAY, "test");
    }

    // ---------- Remote preference ----------

    @Test
    @DisplayName("adjust should subtract 15 when a remote-preferring user sees an onsite ad")
    void remote_whenDescriptionOnsite_shouldSubtractFullPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Trabalho 100% presencial em São Paulo.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 8 when a remote-preferring user sees a hybrid ad")
    void remote_whenDescriptionHybrid_shouldSubtractMildPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Modelo híbrido, 2 dias de home office por semana.", "CompanyX");

        assertEquals(72, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize a remote ad for a remote-preferring user")
    void remote_whenDescriptionRemote_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Vaga remota para qualquer lugar do Brasil.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should still detect onsite when the ad negates remote ('não é remoto')")
    void remote_whenDescriptionNegatesRemote_shouldStillDetectOnsite() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Atuação 100% presencial — não é remoto e não é home office.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should still detect onsite when the ad negates remote with 'não remoto'")
    void remote_whenDescriptionNaoRemoto_shouldStillDetectOnsite() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Atuação presencial — não remoto.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract the full penalty when the ad negates remote with 'sem remoto'")
    void remote_whenDescriptionSemRemotoAndOnsite_shouldSubtractFullPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Vaga sem remoto, 100% presencial.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize a silent ad (no work-model signal)")
    void remote_whenDescriptionSilent_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Java com experiência em Spring Boot.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    // ---------- Hybrid preference ----------

    @Test
    @DisplayName("adjust should subtract 8 for hybrid preference when the ad is remote and no preferred city is mentioned")
    void hybrid_whenDescriptionRemoteAndCityUnknown_shouldSubtractMildPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
        Job job = job("Vaga 100% remota.", "CompanyX");

        assertEquals(72, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize when the ad mentions a preferred city")
    void hybrid_whenDescriptionMentionsPreferredCity_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
        Job job = job("Trabalho híbrido com escritório em Curitiba.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize a silent ad for a hybrid-preferring user")
    void hybrid_whenDescriptionSilent_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Hybrid(List.of("Curitiba")), null, List.of());
        Job job = job("Oportunidade na área de TI.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    // ---------- Onsite preference ----------

    @Test
    @DisplayName("adjust should subtract 15 for onsite preference when the ad is remote and no preferred city is mentioned")
    void onsite_whenDescriptionRemoteAndCityMissing_shouldSubtractFullPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Onsite(List.of("Curitiba")), null, List.of());
        Job job = job("Vaga totalmente remota para todo o Brasil.", "CompanyX");

        assertEquals(65, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 8 for onsite preference when the remote ad still mentions a preferred city")
    void onsite_whenDescriptionRemoteButCityMatch_shouldSubtractMildPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Onsite(List.of("São Paulo")), null, List.of());
        Job job = job("Atuação remota, com escritório disponível em São Paulo.", "CompanyX");

        assertEquals(72, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize an onsite ad in a preferred city for an onsite-preferring user")
    void onsite_whenDescriptionOnsiteInPreferredCity_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Onsite(List.of("São Paulo")), null, List.of());
        Job job = job("Vaga presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    // ---------- Excluded companies ----------

    @Test
    @DisplayName("adjust should cap the score at 15 when the company is excluded (case-insensitive)")
    void excludedCompany_whenCompanyMatches_shouldCapScore() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("Desenvolvedor Java.", "ACME CORP");

        assertEquals(15, JobPreferenceScorer.adjust(90, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not cap when the company does not match any excluded entry")
    void excludedCompany_whenCompanyDoesNotMatch_shouldKeepScore() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("Desenvolvedor Java.", "Globex");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should keep the raw score when it is already below the cap")
    void excludedCompany_whenCoreScoreBelowCap_shouldKeepRawScore() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("Desenvolvedor Java.", "Acme Corp");

        assertEquals(10, JobPreferenceScorer.adjust(10, job, prefs, List.of()));
    }

    // ---------- Seniority mismatch (docs/specs/preferences-scoring.md) ----------

    @Test
    @DisplayName("adjust should subtract 10 when the job title marks the role as pleno")
    void seniority_whenTitlePleno_shouldSubtractSeniorityPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Java Pleno",
                "Desenvolvedor com experiência em Java e Spring Boot.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 10 when the job title marks the role as 'Pl.'")
    void seniority_whenTitleAbbreviatedPl_shouldSubtractSeniorityPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Analista de Sistemas Pl.",
                "Oportunidade na área de sistemas.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 10 when the job title marks the role as 'PL' (uppercase)")
    void seniority_whenTitlePlUppercase_shouldSubtractSeniorityPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Backend PL",
                "Oportunidade na área de backend.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 10 when the job title marks the role as mid-level")
    void seniority_whenTitleMidLevel_shouldSubtractSeniorityPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Mid-Level",
                "Oportunidade na área de tecnologia.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize a junior title")
    void seniority_whenTitleJunior_shouldKeepScore() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Júnior",
                "Oportunidade para quem está começando.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize a title without any seniority marker")
    void seniority_whenTitleWithoutSeniority_shouldKeepScore() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor",
                "Desenvolvedor com experiência em Java.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not match 'pl' inside another word (word-boundary on the PL abbreviation)")
    void seniority_whenTitleContainsPlInsideWord_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor de Aplicação",
                "Oportunidade na área de desenvolvimento.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should combine the seniority penalty with the work-model modifier")
    void seniority_whenTitlePlenoAndWorkModelConflict_shouldCombinePenalties() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Java Pleno",
                "Trabalho 100% presencial em São Paulo.", "CompanyX");

        // -10 (seniority) + -15 (onsite contradicting remote preference) = -25
        assertEquals(55, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should clamp at zero after the seniority penalty is applied")
    void seniority_whenScoreWouldClamp_shouldClampAtZero() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Pleno",
                "Oportunidade na área de tecnologia.", "CompanyX");

        assertEquals(0, JobPreferenceScorer.adjust(4, job, prefs, List.of()));
    }

    // ---------- Seniority-in-body (docs/specs/match-quality.md §3) ----------

    @Test
    @DisplayName("adjust should subtract 10 when the description requires 5 anos de experiência")
    void seniority_whenBodyMentionsFiveYears_shouldSubtractPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor",
                "Requer 5 anos de experiência em Java e Spring Boot.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 10 when the description requires years of experience (EN anchor)")
    void seniority_whenBodyMentionsYearsOfExperience_shouldSubtractPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Developer",
                "Requires 5 years of experience with Spring Boot and AWS.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract 10 when the number comes after the marker (either order)")
    void seniority_whenNumberAfterMarker_shouldSubtractPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor",
                "Requisitos: anos de experiência mínima de 5 em Java.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should fire only for bare integers in the 3..30 band near the marker")
    void seniority_whenBodyYearsAtBandBoundaries_shouldFollowBand() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());

        assertEquals(70, JobPreferenceScorer.adjust(80, job("Requer 3 anos de experiência.", "CompanyX"), prefs, List.of()));
        assertEquals(70, JobPreferenceScorer.adjust(80, job("Requer 30 anos de experiência.", "CompanyX"), prefs, List.of()));
        assertEquals(80, JobPreferenceScorer.adjust(80, job("Requer 2 anos de experiência.", "CompanyX"), prefs, List.of()));
        assertEquals(80, JobPreferenceScorer.adjust(80, job("Requer 35 anos de experiência.", "CompanyX"), prefs, List.of()));
        assertEquals(80, JobPreferenceScorer.adjust(80, job("Requer 1 ano de experiência.", "CompanyX"), prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should ignore years sitting further than 6 tokens from the marker")
    void seniority_whenNumberFarFromMarker_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("A vaga exige 12 profissionais no time e experiência sólida. "
                + "Requer anos de experiência comprovada na função.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should subtract the penalty only once when title and body both hit")
    void seniority_whenTitleAndBodyBothHit_shouldSubtractOnce() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Java Pleno",
                "Requer 5 anos de experiência em Java.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not treat a bare 'pleno' in free-form prose as a body signal")
    void seniority_whenBodyProseMentionsPleno_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor",
                "Atuará em squad pleno, colaborando diretamente com os sêniores.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not apply the body seniority penalty when only salaryFloor is set")
    void seniority_whenOnlySalaryFloorAndBodyYears_shouldKeepScore() {
        UserPreferences prefs = new UserPreferences(null, 5000, List.of());
        Job job = job("Desenvolvedor",
                "Requer 5 anos de experiência em Java.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    // ---------- Soft stack signal (docs/specs/match-quality.md §4) ----------

    @Test
    @DisplayName("adjust should subtract 10 when no profile skill appears in title or description")
    void stack_whenSkillsMissingFromJob_shouldSubtractPenalty() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("COBOL Developer",
                "Vaga para manutenção de sistemas mainframe.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, prefs, List.of("Java", "Spring Boot")));
    }

    @Test
    @DisplayName("adjust should not penalize when a profile skill appears in the title")
    void stack_whenSkillMatchesTitle_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Java Developer",
                "Vaga para desenvolvimento backend.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of("java")));
    }

    @Test
    @DisplayName("adjust should not penalize when a multi-word skill appears in the description")
    void stack_whenMultiWordSkillInDescription_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Developer",
                "Atuação com Spring Boot e microservices.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of("Spring Boot")));
    }

    @Test
    @DisplayName("adjust should not penalize when at least one of several skills overlaps")
    void stack_whenPartialSkillOverlap_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Java Developer",
                "Vaga para desenvolvimento backend.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of("Java", "Kotlin")));
    }

    @Test
    @DisplayName("adjust should match skills case-insensitively (same contains idiom as city detection)")
    void stack_whenTitleUppercaseAndSkillLowercase_shouldNotPenalize() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("JAVA DEVELOPER",
                "Vaga para desenvolvimento backend.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of("java")));
    }

    @Test
    @DisplayName("adjust should not penalize when skills are empty (identity guarantee)")
    void stack_whenEmptySkills_shouldStayIdentical() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("COBOL Developer",
                "Vaga para manutenção de sistemas mainframe.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not penalize when skills are blank after trimming (identity guarantee)")
    void stack_whenBlankSkills_shouldStayIdentical() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("COBOL Developer",
                "Vaga para manutenção de sistemas mainframe.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of(" ", "", "  ")));
    }

    @Test
    @DisplayName("adjust should apply the stack signal for a preference-less profile carrying skills")
    void stack_whenNoPreferencesButSkillsMismatch_shouldSubtractPenalty() {
        Job job = job("COBOL Developer",
                "Vaga para manutenção de sistemas mainframe.", "CompanyX");

        assertEquals(70, JobPreferenceScorer.adjust(80, job, null, List.of("Java")));
    }

    @Test
    @DisplayName("adjust should keep the excluded-company cap above the stack penalty")
    void stack_whenExcludedCompany_shouldCapBeforeStack() {
        UserPreferences prefs = new UserPreferences(null, null, List.of("Acme Corp"));
        Job job = job("COBOL Developer",
                "Vaga para manutenção de sistemas mainframe.", "ACME CORP");

        // excluded cap (15) wins over the -10 stack penalty; never 5.
        assertEquals(15, JobPreferenceScorer.adjust(90, job, prefs, List.of("Java")));
    }

    @Test
    @DisplayName("adjust should compose the stack penalty with work-model and seniority, clamped at zero")
    void stack_whenCombinedWithOtherSignals_shouldComposeAndClamp() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job job = job("Desenvolvedor Java Pleno",
                "Trabalho 100% presencial em São Paulo.", "CompanyX");

        // -10 (seniority via pleno title) + -15 (onsite vs remote) = -25; the
        // Java skill hits the title so the stack signal stays silent.
        assertEquals(55, JobPreferenceScorer.adjust(80, job, prefs, List.of("Java")));
    }

    // ---------- Identity guarantee ----------

    @Test
    @DisplayName("adjust should return the raw score when preferences are null")
    void adjust_whenNoPreferences_shouldReturnRawScore() {
        Job job = job("Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, null, List.of()));
    }

    @Test
    @DisplayName("adjust should return the raw score for a semantically blank preferences object")
    void adjust_whenEmptyPreferences_shouldReturnRawScore() {
        Job job = job("Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, UserPreferences.empty(), List.of()));
    }

    @Test
    @DisplayName("adjust should not change the score when only salaryFloor is set (prompt-only signal)")
    void adjust_whenOnlySalaryFloor_shouldNotChangeScore() {
        UserPreferences prefs = new UserPreferences(null, 5000, List.of());
        Job job = job("Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    @Test
    @DisplayName("adjust should not apply the seniority penalty when only salaryFloor is set (prompt-only guarantee)")
    void seniority_whenOnlySalaryFloorAndPlenoTitle_shouldKeepScore() {
        // PR #80 review P0-3: salaryFloor is a prompt-only signal, so a
        // profile carrying ONLY a salary floor must come out byte-identical —
        // a "Desenvolvedor Pleno" title cannot silently penalize it.
        UserPreferences prefs = new UserPreferences(null, 5000, List.of());
        Job job = job("Desenvolvedor Java Pleno",
                "Vaga 100% presencial em São Paulo.", "CompanyX");

        assertEquals(80, JobPreferenceScorer.adjust(80, job, prefs, List.of()));
    }

    // ---------- Clamping ----------

    @Test
    @DisplayName("adjust should clamp at both bounds after applying the modifier")
    void adjust_whenScoreWouldExceedBounds_shouldClamp() {
        UserPreferences prefs = new UserPreferences(new WorkPreference.Remote(), null, List.of());
        Job onsiteJob = job("Trabalho 100% presencial.", "CompanyX");

        assertEquals(0, JobPreferenceScorer.adjust(5, onsiteJob, prefs, List.of()));
        assertEquals(85, JobPreferenceScorer.adjust(100, onsiteJob, prefs, List.of()));
    }
}