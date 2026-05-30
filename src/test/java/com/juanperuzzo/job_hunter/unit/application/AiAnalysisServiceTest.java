package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.service.AiAnalysisService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private AiAnalysisService aiAnalysisService;

    @BeforeEach
    void setUp() {
        aiAnalysisService = new AiAnalysisService(aiPort, jobAnalysisRepository, userProfileRepository);
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
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Job job = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now());

            JobAnalysis analysis = aiAnalysisService.analyze(1L, job);

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
            Job job = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "", LocalDate.now());

            assertThrows(IllegalArgumentException.class, () -> aiAnalysisService.analyze(1L, job));

            Job jobBlank = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "   ", LocalDate.now());
            assertThrows(IllegalArgumentException.class, () -> aiAnalysisService.analyze(1L, jobBlank));
        }
    }

    @Nested
    @DisplayName("Scenario 3: AI returns invalid JSON")
    class InvalidJsonTests {

        @Test
        @DisplayName("analyze should throw AiException when AI returns invalid JSON")
        void analyze_whenInvalidJson_shouldThrowAiException() {
            when(aiPort.complete(any())).thenReturn("not valid json");
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());

            Job job = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now());

            AiException exception = assertThrows(AiException.class,
                    () -> aiAnalysisService.analyze(1L, job));

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
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());

            Job job = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now());

            assertThrows(AiException.class, () -> aiAnalysisService.analyze(1L, job));
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
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Job job = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now());

            JobAnalysis analysis = aiAnalysisService.analyze(1L, job);

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
            when(userProfileRepository.findByUserId(any())).thenReturn(Optional.empty());
            when(jobAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Job job = new Job(null, "Java Developer", "CompanyX",
                    "https://example.com/job/1", "Description", LocalDate.now());

            JobAnalysis analysis = aiAnalysisService.analyze(1L, job);

            assertEquals(75, analysis.matchScore());
        }
    }
}
