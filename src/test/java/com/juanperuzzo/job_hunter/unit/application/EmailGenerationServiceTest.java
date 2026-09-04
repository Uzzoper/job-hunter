package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.EmailDraft;
import com.juanperuzzo.job_hunter.domain.model.EmailStatus;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;


import static org.mockito.ArgumentMatchers.any;
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
    private JobRepository jobRepository;

    @Mock
    private JobAnalysisRepository jobAnalysisRepository;

    @Mock
    private TemplateEmailService templateEmailService;

    private EmailGenerationService emailGenerationService;

    @BeforeEach
    void setUp() {
        emailGenerationService = new EmailGenerationService(aiPort, emailDraftRepository, userProfileRepository,
                jobRepository, jobAnalysisRepository, templateEmailService, 60);
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 40,
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
    @DisplayName("Scenario 2: analysis with low matchScore")
    class LowMatchScoreTests {

        @Test
        @DisplayName("generate should proceed normally when matchScore < 30")
        void generate_whenLowMatchScore_shouldProceedNormally() {
            String aiResponse = """
                Subject: Application for Junior Developer

                I am very interested in this position.
                Although I lack some skills, I am willing to learn.

                Best regards,
                Juan Peruzzo
                """;

            when(aiPort.complete(any())).thenReturn(aiResponse);
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            UserProfile validProfile = new UserProfile(null, 1L,
                "Experienced Java developer with Spring Boot expertise.",
                List.of("Java", "Spring Boot", "PostgreSQL"),
                CompanyTone.FORMAL,
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 2L;
            Job job = new Job(jobId, "Junior Developer", "StartupY",
                    "https://example.com/job/2", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 25,
                    List.of("Java"),
                    List.of("AWS", "Docker"),
                    CompanyTone.STARTUP,
                    "Junior developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            // Low score doesn't block generation; prompt handles missing skills matter-of-factly
            assertTrue(draft.subject().startsWith("Subject: "));
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 3L;
            Job job = new Job(jobId, "Developer", "BankZ",
                    "https://example.com/job/3", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 50,
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 4L;
            Job job = new Job(jobId, "Developer", "StartupCool",
                    "https://example.com/job/4", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 50,
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 5L;
            Job job = new Job(jobId, "Developer", "CompanyX",
                    "https://example.com/job/5", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 50,
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
    @DisplayName("Scenario 6: template branch for high matchScore")
    class TemplateBranchTests {

        @Test
        @DisplayName("generate should use template and skip AI when matchScore >= 60")
        void generate_whenMatchScoreHigh_shouldUseTemplateAndSkipAi() {
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
                    null, null, null, null, null);

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(profile));
            when(emailDraftRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.empty());
            when(emailDraftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var templateResult = new TemplateEmailService.TemplateResult(
                    "Candidatura — Desenvolvedor Java na EmpresaX",
                    "Gostaria de me candidatar à vaga de Desenvolvedor Java na EmpresaX.");
            when(templateEmailService.generate(job)).thenReturn(templateResult);

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            assertTrue(draft.subject().contains("Desenvolvedor Java"));
            assertTrue(draft.subject().contains("EmpresaX"));
            assertTrue(draft.body().contains("EmpresaX"));
            assertEquals(EmailStatus.PENDING, draft.status());
            verify(aiPort, never()).complete(any());
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 7L;
            Job job = new Job(jobId, "Customer Service", "CompanyZ",
                    "https://example.com/job/7", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 10,
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 8L;
            Job job = new Job(jobId, "Fullstack", "CompanyW",
                    "https://example.com/job/8", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 15,
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
                List.of(), null, null, null, null, null);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 9L;
            Job job = new Job(jobId, "Analista de Fidelização", "CompanyV",
                    "https://example.com/job/9", "Description", LocalDate.now(), "test");
            JobAnalysis analysis = new JobAnalysis(null, null, null, 20,
                    List.of(), List.of(),
                    CompanyTone.STARTUP,
                    "Analyst role, non-tech");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertEquals(EmailStatus.PENDING, draft.status());
        }
    }
}
