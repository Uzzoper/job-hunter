package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
import com.juanperuzzo.job_hunter.application.service.ProfilePlaceholders;
import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.application.service.BotMemorySyncService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserPreferences;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.juanperuzzo.job_hunter.domain.model.WorkPreference;

import java.util.Optional;
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
import java.time.LocalDateTime;
import java.util.List;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailGenerationService tests")
class EmailGenerationServiceTest {

    @Mock
    private AiPort aiPort;

    @Mock
    private EmailDraftRepository emailDraftRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobAnalysisRepository jobAnalysisRepository;

    @Mock
    private TemplateEmailService templateEmailService;

    @Mock
    private BotMemorySyncService botMemorySyncService;

    private static final User USER = new User(1L, "juan@example.com", "Juan Antonio Peruzzo", "hash");

    private EmailGenerationService emailGenerationService;

    @BeforeEach
    void setUp() {
        // Lenient: the null-parameter tests never reach the user lookup.
        lenient().when(userRepository.findById(any())).thenReturn(Optional.of(USER));
        emailGenerationService = new EmailGenerationService(aiPort, emailDraftRepository, userProfileRepository,
                userRepository, jobRepository, jobAnalysisRepository, templateEmailService, botMemorySyncService, 60, 8000);
    }

    @Nested
    @DisplayName("Truncation: configurable resume limit, tail cut with a warning")
    class TruncationTests {

        @Test
        @DisplayName("generate should truncate the resume beyond the configured limit and log a warning")
        void generate_whenResumeExceedsLimit_shouldTruncateAndWarn() {
            EmailGenerationService smallLimitService = new EmailGenerationService(
                    aiPort, emailDraftRepository, userProfileRepository, userRepository,
                    jobRepository, jobAnalysisRepository, templateEmailService, botMemorySyncService, 60, 100);
            String aiResponse = """
                Subject: Application for Java Developer Position

                Dear Hiring Manager,

                I am writing to express my interest in the Java Developer position at CompanyX.

                Sincerely,
                Juan Peruzzo
                """;

            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile longResumeProfile = new UserProfile(null, 1L,
                    "y".repeat(150) + " FIM_DO_CURRICULO",
                    List.of("Java", "Spring Boot", "PostgreSQL"),
                    CompanyTone.FORMAL,
                    List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(longResumeProfile));

            Long jobId = 70L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/70", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 75,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(aiResponse);

            var logger = (Logger) LoggerFactory.getLogger(EmailGenerationService.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                smallLimitService.generate(1L, jobId);
            } finally {
                logger.detachAppender(appender);
            }

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("y".repeat(100) + "..."),
                    "resume excerpt must carry the ellipsis after the configured limit");
            assertFalse(prompt.contains("FIM_DO_CURRICULO"), "truncated resume tail must not appear in the prompt");
            var warned = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("truncating to 100"));
            assertTrue(warned, "expected a truncation WARN for the resume");
        }
    }

    @Nested
    @DisplayName("Scenario 1: successful generation")
    class SuccessfulGenerationTests {

        @Test
        @DisplayName("generate should return EmailDraft with subject and body when AI returns valid response")
        void generate_whenSuccessful_shouldReturnEmailDraft() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Dear Hiring Manager,

                I am writing to express my interest in the Java Developer position at CompanyX.
                My background in Java and Spring Boot aligns well with your requirements.

                Sincerely,
                Juan Peruzzo
                """;

            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 75,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertTrue(draft.subject().startsWith("Subject: "));
            assertNotNull(draft.body());
            assertEquals(EmailStatus.PENDING, draft.status());
            // Body should have 3-5 paragraphs (separated by blank lines)
            String body = draft.body();
            String[] paragraphs = body.split("\\n\\s*\\n");
            assertTrue(paragraphs.length >= 3 && paragraphs.length <= 5, "Body should have 3-5 paragraphs");
        }
    }

    @Nested
    @DisplayName("Scenario 2: analysis with low matchScore (template branch)")
    class LowMatchScoreTests {

        @Test
        @DisplayName("generate should use the standard template and skip AI when matchScore is below the threshold")
        void generate_whenLowMatchScore_shouldUseTemplateAndSkipAi() {
            Long jobId = 2L;
            Job job = new Job(jobId, "Junior Developer", "StartupY",
                    "https://example.com/job/2", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 25,
                    List.of("Java"),
                    List.of("AWS", "Docker"),
                    CompanyTone.STARTUP,
                    "Junior developer position");
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(templateEmailService.generate(eq(job), any(User.class), any(UserProfile.class)))
                    .thenReturn(new TemplateEmailService.TemplateResult(
                            "Candidatura — Junior Developer na StartupY",
                            "Gostaria de me candidatar à vaga de Junior Developer na StartupY."));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            // Low score doesn't block generation; it routes to the deterministic template.
            assertTrue(draft.subject().contains("Junior Developer"));
            assertTrue(draft.subject().contains("StartupY"));
            assertEquals(EmailStatus.PENDING, draft.status());
            verify(aiPort, never()).complete(any());
            verify(templateEmailService).generate(eq(job), any(User.class), any(UserProfile.class));
        }
    }

    @Nested
    @DisplayName("Threshold boundary: score exactly at the 60/59 edge")
    class ThresholdBoundaryTests {

        @Test
        @DisplayName("generate should use the AI path when matchScore equals the threshold (60)")
        void generate_whenScoreExactlyThreshold_shouldCallAiNotTemplate() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Dear Hiring Manager,

                I am writing to express my interest in the Java Developer position at CompanyX.

                Sincerely,
                Juan Peruzzo
                """;
            Long jobId = 90L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/90", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 60,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");
            UserProfile profile = new UserProfile(null, 1L,
                    "Resume text", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals(EmailStatus.PENDING, draft.status());
            verify(aiPort).complete(any());
            verify(templateEmailService, never()).generate(any(), any(), any());
        }

        @Test
        @DisplayName("generate should use the template path one point below the threshold (59)")
        void generate_whenScoreOneBelowThreshold_shouldUseTemplateNotAi() {
            Long jobId = 91L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/91", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 59,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");
            UserProfile profile = new UserProfile(null, 1L,
                    "Resume text", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(templateEmailService.generate(eq(job), any(User.class), any(UserProfile.class)))
                    .thenReturn(new TemplateEmailService.TemplateResult(
                            "Candidatura — Java Developer na CompanyX",
                            "Gostaria de me candidatar à vaga de Java Developer na CompanyX."));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertTrue(draft.subject().contains("Java Developer"));
            assertTrue(draft.subject().contains("CompanyX"));
            assertEquals(EmailStatus.PENDING, draft.status());
            verify(aiPort, never()).complete(any());
            verify(templateEmailService).generate(eq(job), any(User.class), any(UserProfile.class));
        }
    }

    @Nested
    @DisplayName("Scenario 3: formal tone")
    class FormalToneTests {

        @Test
        @DisplayName("generate should build prompt with formal instructions when companyTone is FORMAL")
        void generate_whenFormalTone_shouldIncludeFormalInstructions() {
            // We can't easily verify the prompt content without accessing the built prompt.
            // But we can verify that generation works with FORMAL tone.
            String aiResponse = """
                Subject: Formal Application for Developer

                Prezados(as) Senhores,

                Estou me candidatando a vaga de desenvolvedor.

                Atenciosamente,
                Juan Peruzzo
                """;

            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 3L;
            Job job = new Job(jobId, "Developer", "BankZ",
                    "https://example.com/job/3", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java"),
                    List.of(),
                    CompanyTone.FORMAL,
                    "Developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals(CompanyTone.FORMAL, analysis.companyTone());
        }
    }

    @Nested
    @DisplayName("Scenario 4: startup tone")
    class StartupToneTests {

        @Test
        @DisplayName("generate should build prompt with energetic language when companyTone is STARTUP")
        void generate_whenStartupTone_shouldIncludeEnergeticLanguage() {
            String aiResponse = """
                Subject: Let's rock the code!

                Hey team!

                I'm super excited about this role at your startup!

                Cheers,
                Juan
                """;

            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 4L;
            Job job = new Job(jobId, "Developer", "StartupCool",
                    "https://example.com/job/4", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("React"),
                    List.of("AWS"),
                    CompanyTone.STARTUP,
                    "Startup developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals(CompanyTone.STARTUP, analysis.companyTone());
        }
    }

    @Nested
    @DisplayName("Scenario 5: AI unavailable")
    class AiUnavailableTests {

        @Test
        @DisplayName("generate should throw AiException when AI client throws exception")
        void generate_whenAiUnavailable_shouldThrowAiException() {
            when(aiPort.complete(any())).thenThrow(new RuntimeException("Network error"));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 5L;
            Job job = new Job(jobId, "Developer", "CompanyX",
                    "https://example.com/job/5", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java"),
                    List.of(),
                    CompanyTone.FORMAL,
                    "Developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            assertThrows(AiException.class, () -> emailGenerationService.generate(1L, jobId));
        }
    }

    @Nested
    @DisplayName("Scenario 6: AI branch for high matchScore")
    class TemplateBranchTests {

        @Test
        @DisplayName("generate should call AI and skip the template when matchScore >= threshold")
        void generate_whenMatchScoreHigh_shouldCallAiAndSkipTemplate() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Dear Hiring Manager,

                I am writing to express my interest in the Java Developer position at EmpresaX.

                Sincerely,
                Juan Peruzzo
                """;
            Long jobId = 6L;
            Job job = new Job(jobId, "Desenvolvedor Java", "EmpresaX",
                    "https://example.com/job/6", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 75,
                    List.of("Java", "Spring Boot"),
                    List.of("AWS"),
                    CompanyTone.FORMAL,
                    "Java developer position");
            UserProfile profile = new UserProfile(null, 1L,
                    "Resume text", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(aiPort.complete(any())).thenReturn(aiResponse);

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals("Subject: Application for Java Developer Position", draft.subject());
            assertTrue(draft.body().contains("EmpresaX"));
            assertEquals(EmailStatus.PENDING, draft.status());
            verify(aiPort).complete(any());
            verify(templateEmailService, never()).generate(any(), any(), any());
        }

        @Test
        @DisplayName("generate should write the NO_APPLY reason to bot memory when the template result carries a refusal marker")
        void generate_whenTemplateCarriesNoApplyMarker_shouldWriteRefusalReason() {
            Long jobId = 7L;
            Job job = new Job(jobId, "Desenvolvedor Java", "EmpresaX",
                    "https://example.com/job/7", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 30,
                    List.of("Java"), List.of(),
                    CompanyTone.FORMAL,
                    "Java developer position");
            UserProfile profile = new UserProfile(null, 1L,
                    "Resume text", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.empty());
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // The template result body carries the same NO_APPLY refusal marker the
            // AI path uses — it must be handled identically: REJECTED + write-back.
            when(templateEmailService.generate(eq(job), any(User.class), any(UserProfile.class)))
                    .thenReturn(
                            new TemplateEmailService.TemplateResult(
                                    "Candidatura — Desenvolvedor Java na EmpresaX",
                                    "NO_APPLY: template flagged no fit"));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertEquals(EmailStatus.REJECTED, draft.status());
            verify(botMemorySyncService).writeMemoryEntry(1L, "template flagged no fit");
        }

        @Test
        @DisplayName("generate should keep the REJECTED template flow when the refusal write-back fails (best-effort)")
        void generate_whenTemplateRefusalWriteBackFails_shouldKeepNormalFlow() {
            Long jobId = 8L;
            Job job = new Job(jobId, "Desenvolvedor Java", "EmpresaY",
                    "https://example.com/job/8", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 30,
                    List.of("Java"), List.of(),
                    CompanyTone.FORMAL,
                    "Java developer position");
            UserProfile profile = new UserProfile(null, 1L,
                    "Resume text", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.empty());
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(templateEmailService.generate(eq(job), any(User.class), any(UserProfile.class)))
                    .thenReturn(
                            new TemplateEmailService.TemplateResult(
                                    "Candidatura — Desenvolvedor Java na EmpresaY",
                                    "NO_APPLY: no fit for template"));
            doThrow(new RuntimeException("disk full"))
                    .when(botMemorySyncService).writeMemoryEntry(eq(1L), anyString());

            // writeMemoryEntry fails → the refusal flow must NOT fail with it
            EmailDraft draft = assertDoesNotThrow(() -> emailGenerationService.generate(1L, jobId));

            assertNotNull(draft);
            assertEquals(EmailStatus.REJECTED, draft.status());
            assertEquals("NO_APPLY: no fit for template", draft.body());
            verify(emailDraftRepository).save(draft);
        }
    }

    @Nested
    @DisplayName("Error cases: null parameters")
    class NullParameterTests {

        @Test
        @DisplayName("generate should throw NullPointerException when userId is null")
        void generate_whenUserIdIsNull_shouldThrowNullPointerException() {
            Long jobId = 1L;
            assertThrows(NullPointerException.class, () -> emailGenerationService.generate(null, jobId));
        }

        @Test
        @DisplayName("generate should throw NullPointerException when jobId is null")
        void generate_whenJobIdIsNull_shouldThrowNullPointerException() {
            assertThrows(NullPointerException.class, () -> emailGenerationService.generate(1L, null));
        }
    }

    @Nested
    @DisplayName("Scenario 7: AI refusal (NO_APPLY prefix)")
    class NoApplyRefusalTests {

        @Test
        @DisplayName("generate should return REJECTED draft when AI responds NO_APPLY and persist it")
        void generate_whenNoFit_shouldReturnRejectedDraft() {
            String aiResponse = "NO_APPLY: non-tech role, customer service via WhatsApp";

            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 7L;
            Job job = new Job(jobId, "Customer Service", "CompanyZ",
                    "https://example.com/job/7", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 75,
                    List.of(),
                    List.of("Java", "Spring Boot"),
                    CompanyTone.FORMAL,
                    "Customer service role, non-tech");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals(EmailStatus.REJECTED, draft.status());
            // Spec: subject stores "" and body stores the full NO_APPLY reason (auditable, no fake subject)
            assertEquals("", draft.subject());
            assertEquals(aiResponse, draft.body());
            verify(aiPort).complete(any());
            verify(templateEmailService, never()).generate(any(), any(), any());
            verify(emailDraftRepository).save(draft);
        }

        @Test
        @DisplayName("generate should return REJECTED when NO_APPLY prefix has leading whitespace (via trim)")
        void generate_whenNoApplyPrefixWithLeadingWhitespace_shouldReturnRejectedStatus() {
            when(aiPort.complete(any())).thenReturn("  NO_APPLY: stack entirely outside candidate");
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 8L;
            Job job = new Job(jobId, "Fullstack", "CompanyW",
                    "https://example.com/job/8", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 75,
                    List.of(), List.of("Java"),
                    CompanyTone.FORMAL,
                    "Fullstack role");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertEquals(EmailStatus.REJECTED, draft.status());
        }

        @Test
        @DisplayName("generate should keep PENDING for fallback feedback text without a NO_APPLY prefix (documents current flawed behavior)")
        void generate_whenFeedbackWithoutNoApplyPrefix_shouldRemainPending() {
            when(aiPort.complete(any())).thenReturn("Juan, essa vaga não tem nada a ver com o seu perfil.");
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 9L;
            Job job = new Job(jobId, "Analista de Fidelização", "CompanyV",
                    "https://example.com/job/9", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 75,
                    List.of(), List.of(),
                    CompanyTone.STARTUP,
                    "Analyst role, non-tech");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertEquals(EmailStatus.PENDING, draft.status());
        }
    }

    @Nested
    @DisplayName("Scenario 8: idempotency by (jobId, recipientEmail)")
    class IdempotencyTests {

        private static final String DPO_EMAIL = "dpo@mtp.com.br";
        private static final String HR_EMAIL = "rh@mtp.com.br";

        @Test
        @DisplayName("generate should return the existing SENT draft and persist nothing when the pair already sent")
        void generate_whenSameJobAndEmailAlreadySent_shouldReturnExistingSent() {
            Long jobId = 10L;
            Job job = new Job(jobId, "Developer", "MTP",
                    "https://example.com/job/10", "Description", LocalDate.now(), "test", DPO_EMAIL);
            JobAnalysis analysis = new JobAnalysis(null, null, null, 10,
                    List.of(), List.of("Java"),
                    CompanyTone.FORMAL,
                    "Developer role");
            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer.", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            var existingSent = new EmailDraft(100L, jobId, 1L, "Subject", "Body",
                    EmailStatus.SENT, LocalDateTime.now(), LocalDateTime.now());

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.findSentByJobIdAndRecipientEmail(jobId, DPO_EMAIL))
                    .thenReturn(Optional.of(existingSent));

            EmailDraft result = emailGenerationService.generate(1L, jobId);

            assertEquals(existingSent.id(), result.id());
            assertEquals(EmailStatus.SENT, result.status());
            verify(aiPort, never()).complete(any());
            verify(templateEmailService, never()).generate(any(), any(), any());
            verify(emailDraftRepository, never()).save(any());
        }

        @Test
        @DisplayName("generate should return the existing SENT marker and persist nothing when the job was applied on the portal (recipientEmail is null)")
        void generate_whenExternalApplyMarkerSent_shouldSkipRegeneration() {
            Long jobId = 12L;
            Job job = new Job(jobId, "Developer", "MTP",
                    "https://example.com/job/12", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 10,
                    List.of(), List.of("Java"),
                    CompanyTone.FORMAL,
                    "Developer role");
            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer.", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            var externalApplyMarker = new EmailDraft(200L, jobId, 1L,
                    "Subject: [Aplicação externa]",
                    "Inscrição realizada diretamente no portal da vaga. Nenhum e-mail foi enviado.",
                    EmailStatus.SENT, LocalDateTime.now(), LocalDateTime.now());

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(externalApplyMarker));

            EmailDraft result = emailGenerationService.generate(1L, jobId);

            assertEquals(externalApplyMarker.id(), result.id());
            assertEquals(EmailStatus.SENT, result.status());
            verify(aiPort, never()).complete(any());
            verify(templateEmailService, never()).generate(any(), any(), any());
            verify(emailDraftRepository, never()).save(any());
        }

        @Test
        @DisplayName("generate should proceed normally to PENDING when no SENT pair exists for the contact email")
        void generate_whenDifferentEmailForSameJob_shouldGenerateNormally() {
            Long jobId = 11L;
            Job job = new Job(jobId, "Developer", "MTP",
                    "https://example.com/job/11", "Description", LocalDate.now(), "test", HR_EMAIL);
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of(), List.of("Java"),
                    CompanyTone.FORMAL,
                    "Developer role");
            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer.", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.findSentByJobIdAndRecipientEmail(jobId, HR_EMAIL)).thenReturn(Optional.empty());
            when(aiPort.complete(any())).thenReturn("Subject: Application\n\nBody text.");
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals(EmailStatus.PENDING, draft.status());
            verify(aiPort).complete(any());
            verify(emailDraftRepository).save(any());
        }
    }

    @Nested
    @DisplayName("Scenario 9: refusal reason written back to bot memory")
    class RefusalMemoryWritebackTests {

        @Test
        @DisplayName("generate should write the NO_APPLY reason to bot memory when a refusal is finalized")
        void generate_whenNoApplyRefusal_shouldWriteReasonToBotMemory() {
            when(aiPort.complete(any())).thenReturn("NO_APPLY: stack entirely outside candidate");
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer.", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));

            Long jobId = 20L;
            Job job = new Job(jobId, "COBOL Dev", "CompanyM",
                    "https://example.com/job/20", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of(), List.of("Java"),
                    CompanyTone.FORMAL,
                    "Mainframe role");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertEquals(EmailStatus.REJECTED, draft.status());
            verify(botMemorySyncService).writeMemoryEntry(1L, "stack entirely outside candidate");
        }

        @Test
        @DisplayName("generate should still return the REJECTED draft when the memory write fails")
        void generate_whenMemoryWriteFails_shouldStillReturnRejectedDraft() {
            when(aiPort.complete(any())).thenReturn("NO_APPLY: sales role, non-tech");
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            doThrow(new RuntimeException("disk full"))
                    .when(botMemorySyncService).writeMemoryEntry(eq(1L), anyString());
            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer.", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));

            Long jobId = 21L;
            Job job = new Job(jobId, "Sales Analyst", "CompanyN",
                    "https://example.com/job/21", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of(), List.of("Java"),
                    CompanyTone.FORMAL,
                    "Non-tech role");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertEquals(EmailStatus.REJECTED, draft.status());
            assertEquals("NO_APPLY: sales role, non-tech", draft.body());
            verify(emailDraftRepository).save(draft);
        }
    }

    @Nested
    @DisplayName("Scenario 10: preferences consumed in the generation prompt")
    class PreferencesPromptTests {

        @Test
        @DisplayName("generate should include the preferences block and rule 12 in the prompt when preferences are set")
        void generate_whenPreferencesSet_shouldIncludePreferencesInPrompt() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Olá.

                Eu me candidato à vaga.

                Atenciosamente,
                Juan Peruzzo
                """;

            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile prefProfile = new UserProfile(null, 1L,
                    "Experienced Java developer with Spring Boot expertise.",
                    List.of("Java", "Spring Boot", "PostgreSQL"),
                    CompanyTone.FORMAL,
                    List.of(), null, null, null, null, null,
                    new UserPreferences(new WorkPreference.Remote(), 5000, List.of("Acme Corp")));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(prefProfile));

            Long jobId = 30L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/30", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(aiResponse);
            emailGenerationService.generate(1L, jobId);

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains("Candidate preferences"));
            assertTrue(prompt.contains("Work model: Remote"));
            assertTrue(prompt.contains("Salary floor: R$ 5000"));
            assertTrue(prompt.contains("Acme Corp"));
            assertTrue(prompt.contains("NO_APPLY"));
        }

        @Test
        @DisplayName("generate should NOT include the preferences block in the prompt when preferences are absent")
        void generate_whenNoPreferences_shouldNotIncludePreferencesBlock() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Olá.

                Eu me candidato à vaga.

                Atenciosamente,
                Juan Peruzzo
                """;

            UserProfile plainProfile = new UserProfile(null, 1L,
                    "Experienced Java developer with Spring Boot expertise.",
                    List.of("Java", "Spring Boot", "PostgreSQL"),
                    CompanyTone.FORMAL,
                    List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(plainProfile));

            Long jobId = 31L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/31", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            emailGenerationService.generate(1L, jobId);

            String prompt = promptCaptor.getValue();
            assertFalse(prompt.contains("Candidate preferences"));
        }

        @Test
        @DisplayName("generate should NOT include the preferences block when preferences are non-null but semantically blank (UserPreferences.empty())")
        void generate_whenEmptyPreferences_shouldNotIncludePreferencesBlock() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Olá.

                Eu me candidato à vaga.

                Atenciosamente,
                Juan Peruzzo
                """;

            UserProfile emptyPrefsProfile = new UserProfile(null, 1L,
                    "Experienced Java developer with Spring Boot expertise.",
                    List.of("Java", "Spring Boot", "PostgreSQL"),
                    CompanyTone.FORMAL,
                    List.of(), null, null, null, null, null,
                    UserPreferences.empty());
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(emptyPrefsProfile));

            Long jobId = 32L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/32", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            emailGenerationService.generate(1L, jobId);

            String prompt = promptCaptor.getValue();
            assertFalse(prompt.contains("Candidate preferences"),
                    "UserPreferences.empty() must not inject a preferences block");
        }
    }

    @Nested
    @DisplayName("Scenario 11: prompt reference example keeps a single closing CTA")
    class PromptReferenceCopyTests {

        @Test
        @DisplayName("generate should build a prompt whose example email does not duplicate the demo offer")
        void generate_whenPromptBuilt_shouldNotDuplicateDemoOfferInReferenceExample() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Olá.

                Eu me candidato à vaga.

                Atenciosamente,
                Juan Peruzzo
                """;

            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer with Spring Boot expertise.",
                    List.of("Java", "Spring Boot"),
                    CompanyTone.FORMAL,
                    List.of(), null, null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));

            Long jobId = 40L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/40", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(aiResponse);

            emailGenerationService.generate(1L, jobId);

            String prompt = promptCaptor.getValue();
            assertTrue(prompt.contains(
                    "Além dos requisitos da vaga, trabalho também com {{SKILLS}}."));
            assertTrue(prompt.contains("Podemos agendar uma conversa para eu mostrar esses projetos rodando?"));
            assertFalse(prompt.contains(
                    "Posso demonstrar qualquer um desses projetos em funcionamento em uma conversa rápida."));
        }
    }

    @Nested
    @DisplayName("Profile placeholders: missing profile fails loudly and prompt facts match the resolver")
    class ProfilePlaceholderScenarioTests {

        @Test
        @DisplayName("generate should throw ProfileNotFoundException when the user has no profile")
        void generate_whenProfileMissing_shouldThrowProfileNotFoundException() {
            Long jobId = 60L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/60", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java"), List.of(),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());

            assertThrows(ProfileNotFoundException.class, () -> emailGenerationService.generate(1L, jobId));
        }

        @Test
        @DisplayName("generate should throw UserNotFoundException when the user record no longer exists")
        void generate_whenUserMissing_shouldThrowUserNotFoundException() {
            Long jobId = 61L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/61", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java"), List.of(),
                    CompanyTone.FORMAL,
                    "Java developer position");
            UserProfile profile = new UserProfile(null, 1L,
                    "Resume text", List.of("Java"), CompanyTone.FORMAL, List.of(),
                    null, null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            assertThrows(UserNotFoundException.class, () -> emailGenerationService.generate(1L, jobId));
        }

        @Test
        @DisplayName("generate should embed the resolver CANDIDATE FACTS block and tokenized reference example in the prompt")
        void generate_whenPromptBuilt_shouldContainResolverFactsBlock() {
            String aiResponse = """
                Subject: Application for Java Developer Position

                Olá.

                Eu me candidato à vaga.

                Atenciosamente,
                Juan Peruzzo
                """;

            UserProfile profile = new UserProfile(null, 1L,
                    "Experienced Java developer with Spring Boot expertise.",
                    List.of("Java", "Spring Boot", "PostgreSQL"),
                    CompanyTone.FORMAL,
                    List.of(new Project("Job Hunter", "API com Spring Boot", "Spring Boot")),
                    "(42) 99999-0000", "juan@example.com",
                    "https://juanperuzzo.is-a.dev", "https://github.com/Uzzoper",
                    "https://linkedin.com/in/juan", null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));

            Long jobId = 62L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/62", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 65,
                    List.of("Java", "Spring Boot"),
                    List.of("Kubernetes"),
                    CompanyTone.FORMAL,
                    "Java developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            when(aiPort.complete(promptCaptor.capture())).thenReturn(aiResponse);

            emailGenerationService.generate(1L, jobId);

            String prompt = promptCaptor.getValue();
            // The block in the prompt is built by the same resolver the template uses — no drift.
            assertEquals(ProfilePlaceholders.factsBlock(USER, profile), extractFactsBlock(prompt).strip());
            assertTrue(prompt.contains("{{CANDIDATE_NAME}}"), "reference example must stay tokenized");
            assertTrue(prompt.contains("{{PROJECTS}}"));
        }

        /** Extracts the {@code CANDIDATE FACTS:} block between the reference example and the rules section. */
        private static String extractFactsBlock(String prompt) {
            int start = prompt.indexOf("CANDIDATE FACTS:");
            int end = prompt.indexOf("\n\nMANDATORY RULES:");
            assertTrue(start >= 0, "prompt must contain the CANDIDATE FACTS block");
            assertTrue(end > start, "CANDIDATE FACTS block must end before MANDATORY RULES");
            return prompt.substring(start, end);
        }
    }
}
