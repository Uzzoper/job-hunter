package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.service.AiAnalysisService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiAnalysisService tests")
class AiAnalysisServiceTest {

    @Mock
    private AiPort aiPort;

    @Mock
    private JobAnalysisRepository jobAnalysisRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private JobRepository jobRepository;

    private AiAnalysisService aiAnalysisService;

    private UserProfile defaultProfile;

    @BeforeEach
    void setUp() {
        aiAnalysisService = new AiAnalysisService(aiPort, jobAnalysisRepository, userProfileRepository, jobRepository, 8000, 8000);
        defaultProfile = new UserProfile(1L, 1L, "Experienced Java developer",
                List.of("Java", "Spring Boot", "PostgreSQL"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("Truncation: configurable limits, tail survival past the old 1000-char cut")
    class TruncationTests {

        private static final String VALID_JSON = """
            {
              "matchScore": 80,
              "matchedSkills": ["Java"],
              "missingSkills": [],
              "companyTone": "formal",
              "summary": "Developer position"
            }
            """;

        private Job jobWith(String description) {
            return new Job(1L, "Java Developer", "CompanyX",
                    "https://example.com/job/1", description, LocalDate.now(), "test");
        }

        /** Attaches a real logback appender to the service logger, runs the action and returns the emitted messages. */
        private List<String> captureWarnings(Runnable action) {
            var logger = (Logger) LoggerFactory.getLogger(AiAnalysisService.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                action.run();
            } finally {
                logger.detachAppender(appender);
            }
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Test
        @DisplayName("analyze should keep the stack/requirements tail when the description is above the old 1000-char cut")
        void analyze_whenDescriptionBetweenOldAndNewCut_shouldKeepTailInPrompt() {
            String requirementTail = "Requisitos: Java, Spring Boot, PostgreSQL. Requer 5 anos de experiência.";
            String loops = "Descrição da vaga com detalhes de stack e requisitos exigidos. ";
            String description = loops.repeat(25) + requirementTail; // ~1500 chars > old 1000 cut, < 8000
            assertTrue(description.length() > 1000, "fixture must exceed the OLD cut");
            assertTrue(description.length() < 8000, "fixture must stay under the NEW limit");

            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON);
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(description)));

            aiAnalysisService.analyze(1L, 1L);

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("Requisitos: Java, Spring Boot, PostgreSQL"),
                    "stack/requirements section must survive in the prompt, got: " + prompt);
            assertTrue(prompt.contains("Requer 5 anos de experiência"),
                    "years-of-experience tail must survive in the prompt");
        }

        @Test
        @DisplayName("analyze should truncate the description beyond the configured limit and log a warning")
        void analyze_whenDescriptionExceedsConfiguredLimit_shouldTruncateAndWarn() {
            int smallLimit = 100;
            AiAnalysisService smallLimitService = new AiAnalysisService(
                    aiPort, jobAnalysisRepository, userProfileRepository, jobRepository, 8000, smallLimit);
            String description = "x".repeat(150) + " REQUISITOS_FIM_DA_DESCRICAO";
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON);
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith(description)));

            var warnings = captureWarnings(() -> smallLimitService.analyze(1L, 1L));

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("x".repeat(100) + "..."),
                    "description excerpt must carry the ellipsis after the configured limit");
            assertFalse(prompt.contains("REQUISITOS_FIM_DA_DESCRICAO"),
                    "truncated tail must not appear in the prompt");
            assertTrue(warnings.stream().anyMatch(m -> m.contains("truncating to 100")),
                    "expected a truncation WARN for the description, got: " + warnings);
        }

        @Test
        @DisplayName("analyze should truncate the resume beyond the configured limit and log a warning")
        void analyze_whenResumeExceedsConfiguredLimit_shouldTruncateAndWarn() {
            int smallLimit = 100;
            AiAnalysisService smallLimitService = new AiAnalysisService(
                    aiPort, jobAnalysisRepository, userProfileRepository, jobRepository, smallLimit, 8000);
            UserProfile longResumeProfile = new UserProfile(1L, 1L,
                    "y".repeat(150) + " FIM_DO_CURRICULO",
                    List.of("Java", "Spring Boot", "PostgreSQL"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(longResumeProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON);
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(jobWith("Atuação 100% remota.")));

            var warnings = captureWarnings(() -> smallLimitService.analyze(1L, 1L));

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("y".repeat(100) + "..."),
                    "resume excerpt must carry the ellipsis after the configured limit");
            assertFalse(prompt.contains("FIM_DO_CURRICULO"), "truncated resume tail must not appear in the prompt");
            assertTrue(warnings.stream().anyMatch(m -> m.contains("truncating to 100")),
                    "expected a truncation WARN for the resume, got: " + warnings);
        }
    }

    @Nested
    @DisplayName("Prompt v4.1: score-down factors for seniority and degree")
    class PromptV41Tests {

        private static final String VALID_JSON = """
            {
              "matchScore": 80,
              "matchedSkills": ["Java"],
              "missingSkills": ["Go"],
              "companyTone": "formal",
              "summary": "Developer position"
            }
            """;

        @Test
        @DisplayName("analyze should embed the v4.1 score-down factors and still parse the 5-field response")
        void analyze_whenPromptBuilt_shouldContainV41ScoreDownFactors() {
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON);
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(
                    new Job(1L, "Java Developer", "CompanyX",
                            "https://example.com/job/1", "Description", LocalDate.now(), "test")));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, 1L);

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("years of experience above the candidate's junior level"),
                    "prompt must carry the years-of-experience score-down factor, got: " + prompt);
            assertTrue(prompt.contains("2027 graduate"),
                    "prompt must carry the required-degree score-down factor, got: " + prompt);
            assertTrue(prompt.contains("80-100") && prompt.contains("0-19"),
                    "prompt must keep the 5-band grading, got: " + prompt);
            // v4.1 keeps the response format UNCHANGED — the same 5 fields still parse
            assertEquals(80, analysis.matchScore());
            assertEquals(List.of("Java"), analysis.matchedSkills());
            assertEquals(List.of("Go"), analysis.missingSkills());
            assertEquals(CompanyTone.FORMAL, analysis.companyTone());
            assertEquals("Developer position", analysis.summary());
        }
    }

    @Nested
    @DisplayName("Scenario 1: successful analysis")
    class SuccessfulAnalysisTests {

        @Test
        @DisplayName("analyze should return JobAnalysis with valid data when AI returns valid JSON")
        void analyze_whenSuccessful_shouldReturnJobAnalysis() {
            String validJson = """
                {
                  "matchScore": 85,
                  "matchedSkills": ["Java", "Spring Boot"],
                  "missingSkills": ["Kubernetes"],
                  "companyTone": "formal",
                  "summary": "Backend Java developer position"
                }
                """;

            when(aiPort.complete(any())).thenReturn(validJson);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, jobId);

            assertNotNull(analysis);
            assertEquals(85, analysis.matchScore());
            assertEquals(List.of("Java", "Spring Boot"), analysis.matchedSkills());
            assertEquals(List.of("Kubernetes"), analysis.missingSkills());
            assertEquals(CompanyTone.FORMAL, analysis.companyTone());
            assertEquals("Backend Java developer position", analysis.summary());
        }
    }

    @Nested
    @DisplayName("Scenario 2: job with empty description")
    class EmptyDescriptionTests {

        @Test
        @DisplayName("analyze should throw IllegalArgumentException when job has empty description")
        void analyze_whenEmptyDescription_shouldThrowIllegalArgumentException() {
            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            assertThrows(IllegalArgumentException.class, () -> aiAnalysisService.analyze(1L, jobId));

            Job jobBlank = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "   ", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(jobBlank));

            assertThrows(IllegalArgumentException.class, () -> aiAnalysisService.analyze(1L, jobId));
        }
    }

    @Nested
    @DisplayName("Scenario 3: AI returns invalid JSON")
    class InvalidJsonTests {

        @Test
        @DisplayName("analyze should throw AiException when AI returns invalid JSON")
        void analyze_whenInvalidJson_shouldThrowAiException() {
            when(aiPort.complete(any())).thenReturn("not valid json");
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            AiException exception = assertThrows(AiException.class,
                    () -> aiAnalysisService.analyze(1L, jobId));

            assertTrue(exception.getMessage().contains("parse") ||
                       exception.getMessage().contains("invalid"));
        }
    }

    @Nested
    @DisplayName("Scenario 4: AI unavailable")
    class AiUnavailableTests {

        @Test
        @DisplayName("analyze should propagate AiException when AI client throws exception")
        void analyze_whenAiUnavailable_shouldPropagateAiException() {
            when(aiPort.complete(any())).thenThrow(new RuntimeException("Network error"));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            assertThrows(AiException.class, () -> aiAnalysisService.analyze(1L, jobId));
        }
    }

    @Nested
    @DisplayName("Scenario 5: matchScore out of range")
    class OutOfRangeScoreTests {

        @Test
        @DisplayName("analyze should clamp matchScore to 100 when AI returns value above 100")
        void analyze_whenScoreAbove100_shouldClampTo100() {
            String jsonAbove100 = """
                {
                  "matchScore": 150,
                  "matchedSkills": ["Java"],
                  "missingSkills": [],
                  "companyTone": "casual",
                  "summary": "Developer position"
                }
                """;

            when(aiPort.complete(any())).thenReturn(jsonAbove100);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, jobId);

            assertEquals(100, analysis.matchScore());
        }

        @Test
        @DisplayName("analyze should accept matchScore within valid range")
        void analyze_whenScoreInRange_shouldReturnAsIs() {
            String jsonInRange = """
                {
                  "matchScore": 75,
                  "matchedSkills": ["Java"],
                  "missingSkills": [],
                  "companyTone": "startup",
                  "summary": "Developer position"
                }
                """;

            when(aiPort.complete(any())).thenReturn(jsonInRange);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, jobId);

            assertEquals(75, analysis.matchScore());
        }
    }

    @Nested
    @DisplayName("Scenario 6: profile not configured")
    class ProfileNotConfiguredTests {

        @Test
        @DisplayName("analyze should throw ProfileNotConfiguredException when no profile exists for user")
        void analyze_whenNoProfile_shouldThrowProfileNotConfiguredException() {
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

            assertThrows(ProfileNotConfiguredException.class,
                    () -> aiAnalysisService.analyze(1L, jobId));
        }
    }

    @Nested
    @DisplayName("Scenario 7: preferences consumed in scoring and prompt")
    class PreferencesScoringTests {

        private static final String VALID_JSON = """
            {
              "matchScore": %d,
              "matchedSkills": ["Java"],
              "missingSkills": [],
              "companyTone": "formal",
              "summary": "Developer position"
            }
            """;

        private Job remoteUserConflictingJob() {
            // "não é remoto" negates the remote signal — must still register as onsite (S) for a Remote user.
            return new Job(1L, "Java Developer", "CompanyX",
                    "https://example.com/job/1",
                    "Atuação 100% presencial em São Paulo — não é remoto.",
                    LocalDate.now(), "test");
        }

        @Test
        @DisplayName("analyze should persist the preference-adjusted score when a remote-preferring user analyzes an onsite job")
        void analyze_whenRemotePreferenceAndOnsiteJob_shouldPersistAdjustedScore() {
            UserProfile prefProfile = new UserProfile(1L, 1L, "Experienced Java developer",
                    List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null,
                    new UserPreferences(new WorkPreference.Remote(), null, List.of()));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(prefProfile));
            when(aiPort.complete(any())).thenReturn(VALID_JSON.formatted(80));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(remoteUserConflictingJob()));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, 1L);

            assertEquals(65, analysis.matchScore());
        }

        @Test
        @DisplayName("analyze should cap the score at 15 when the job belongs to an excluded company")
        void analyze_whenExcludedCompany_shouldCapScore() {
            UserProfile prefProfile = new UserProfile(1L, 1L, "Experienced Java developer",
                    List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null,
                    new UserPreferences(null, null, List.of("Acme Corp")));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(prefProfile));
            when(aiPort.complete(any())).thenReturn(VALID_JSON.formatted(90));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            Job job = new Job(1L, "Java Developer", "ACME CORP",
                    "https://example.com/job/1", "Desenvolvedor Java.", LocalDate.now(), "test");
            when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, 1L);

            assertEquals(15, analysis.matchScore());
        }

        @Test
        @DisplayName("analyze should keep the raw AI score unchanged when the profile has no preferences")
        void analyze_whenNoPreferences_shouldKeepRawScoreUnchanged() {
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            when(aiPort.complete(any())).thenReturn(VALID_JSON.formatted(80));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(
                    new Job(1L, "Java Developer", "CompanyX", "https://example.com/job/1",
                            "Atuação 100% presencial em São Paulo.", LocalDate.now(), "test")));

            JobAnalysis analysis = aiAnalysisService.analyze(1L, 1L);

            assertEquals(80, analysis.matchScore());
        }

        @Test
        @DisplayName("analyze should include the preferences block in the prompt when preferences are set")
        void analyze_whenPreferencesSet_shouldIncludeThemInPrompt() {
            UserProfile prefProfile = new UserProfile(1L, 1L, "Experienced Java developer",
                    List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null,
                    new UserPreferences(new WorkPreference.Remote(), 5000, List.of("Acme Corp")));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(prefProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON.formatted(80));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(remoteUserConflictingJob()));

            aiAnalysisService.analyze(1L, 1L);

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("Candidate preferences"));
            assertTrue(prompt.contains("Work model: Remote"));
            assertTrue(prompt.contains("Salary floor: R$ 5000"));
            assertTrue(prompt.contains("Acme Corp"));
            assertTrue(prompt.contains("hard skip"));
        }

        @Test
        @DisplayName("analyze should NOT include the preferences block in the prompt when preferences are absent")
        void analyze_whenNoPreferences_shouldNotIncludePreferencesBlock() {
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(defaultProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON.formatted(80));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(
                    new Job(1L, "Java Developer", "CompanyX", "https://example.com/job/1",
                            "Atuação 100% presencial em São Paulo.", LocalDate.now(), "test")));

            aiAnalysisService.analyze(1L, 1L);

            String prompt = promptCaptor.getValue();
            assertFalse(prompt.contains("Candidate preferences"));
        }

        @Test
        @DisplayName("analyze should NOT include the preferences block when preferences are non-null but semantically blank (UserPreferences.empty())")
        void analyze_whenEmptyPreferences_shouldNotIncludePreferencesBlock() {
            UserProfile emptyPrefsProfile = new UserProfile(1L, 1L, "Experienced Java developer",
                    List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null,
                    UserPreferences.empty());
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(emptyPrefsProfile));
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(VALID_JSON.formatted(80));
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(jobRepository.findById(1L)).thenReturn(Optional.of(
                    new Job(1L, "Java Developer", "CompanyX", "https://example.com/job/1",
                            "Atuação 100% presencial em São Paulo.", LocalDate.now(), "test")));

            aiAnalysisService.analyze(1L, 1L);

            String prompt = promptCaptor.getValue();
            assertFalse(prompt.contains("Candidate preferences"),
                    "UserPreferences.empty() must not inject a preferences block");
        }
    }
}
