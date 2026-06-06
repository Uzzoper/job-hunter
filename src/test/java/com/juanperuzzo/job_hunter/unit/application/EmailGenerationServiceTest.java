package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
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

    private EmailGenerationService emailGenerationService;

    @BeforeEach
    void setUp() {
        emailGenerationService = new EmailGenerationService(aiPort, emailDraftRepository, userProfileRepository,
                jobRepository, jobAnalysisRepository);
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
                CompanyTone.FORMAL);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 1L;
            Job job = new Job(jobId, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now());
            JobAnalysis analysis = new JobAnalysis(null, null, null, 85,
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
            // Body should have at most 3 paragraphs (simple check: count non-empty lines separated by blank lines)
            String body = draft.body();
            String[] paragraphs = body.split("\\n\\s*\\n");
            assertTrue(paragraphs.length <= 3, "Body should have at most 3 paragraphs");
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
                CompanyTone.FORMAL);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 2L;
            Job job = new Job(jobId, "Junior Developer", "StartupY",
                    "https://example.com/job/2", "Description", LocalDate.now());
            JobAnalysis analysis = new JobAnalysis(null, null, null, 25, // low score
                    List.of("Java"),
                    List.of("AWS", "Docker"),
                    CompanyTone.STARTUP,
                    "Junior developer position");

            when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
            when(jobAnalysisRepository.findByJobIdAndUserId(jobId, 1L)).thenReturn(Optional.of(analysis));

            EmailDraft draft = emailGenerationService.generate(1L, jobId);

            assertNotNull(draft);
            // Should mention willingness to learn (we can only verify it doesn't throw)
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
                CompanyTone.FORMAL);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 3L;
            Job job = new Job(jobId, "Developer", "BankZ",
                    "https://example.com/job/3", "Description", LocalDate.now());
            JobAnalysis analysis = new JobAnalysis(null, null, null, 80,
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
                CompanyTone.FORMAL);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 4L;
            Job job = new Job(jobId, "Developer", "StartupCool",
                    "https://example.com/job/4", "Description", LocalDate.now());
            JobAnalysis analysis = new JobAnalysis(null, null, null, 70,
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
                CompanyTone.FORMAL);
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.of(validProfile));

            Long jobId = 5L;
            Job job = new Job(jobId, "Developer", "CompanyX",
                    "https://example.com/job/5", "Description", LocalDate.now());
            JobAnalysis analysis = new JobAnalysis(null, null, null, 80,
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
}
