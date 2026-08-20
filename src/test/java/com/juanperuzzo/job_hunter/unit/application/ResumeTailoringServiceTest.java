package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.PdfRendererPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeTailoringService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.AnalysisNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResumeTailoringService tests")
class ResumeTailoringServiceTest {

    private static final long USER_ID = 1L;
    private static final long JOB_ID = 10L;
    private static final String RESUME_TEXT = """
            JUAN ANTONIO PERUZZO
            HABILIDADES TÉCNICAS
            Linguagens: Java, TypeScript, SQL
            Frameworks: Spring Boot, Next.js, React
            Banco de Dados: PostgreSQL
            """;

    @Mock
    private AiPort aiPort;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobAnalysisRepository jobAnalysisRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private PdfRendererPort pdfRendererPort;

    @Captor
    private ArgumentCaptor<String> promptCaptor;

    @Captor
    private ArgumentCaptor<String> htmlCaptor;

    private ResumeTailoringService service;

    private void newService(int maxAiChars) {
        service = new ResumeTailoringService(aiPort, jobRepository, jobAnalysisRepository,
                userProfileRepository, pdfRendererPort, maxAiChars);
    }

    @Test
    @DisplayName("tailorResume should return PDF bytes when job, analysis and profile exist")
    void tailorResume_whenValid_shouldReturnPdfBytes() {
        newService(8000);
        var pdfBytes = new byte[]{37, 80, 68, 70, 1, 2, 3};

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(pdfBytes);

        var result = service.tailorResume(USER_ID, JOB_ID);

        assertSame(pdfBytes, result);
        verify(pdfRendererPort).renderPdf(htmlCaptor.capture());
        assertTrue(htmlCaptor.getValue().contains("Desenvolvedor Java Júnior com foco em Spring Boot"),
                "rendered HTML must contain the tailored objective");
        assertTrue(htmlCaptor.getValue().contains("Java"),
                "rendered HTML must contain matched skills");
    }

    @Test
    @DisplayName("tailorResume should throw ProfileNotConfiguredException when no profile exists")
    void tailorResume_whenNoProfile_shouldThrowProfileNotConfigured() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ProfileNotConfiguredException.class,
                () -> service.tailorResume(USER_ID, JOB_ID));
        verifyNoInteractions(aiPort, pdfRendererPort);
    }

    @Test
    @DisplayName("tailorResume should throw ProfileNotConfiguredException when resumeText is blank")
    void tailorResume_whenBlankResumeText_shouldThrowProfileNotConfigured() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(new UserProfile(1L, USER_ID, "   ", List.of(), CompanyTone.FORMAL, List.of())));

        assertThrows(ProfileNotConfiguredException.class,
                () -> service.tailorResume(USER_ID, JOB_ID));
        verifyNoInteractions(aiPort, pdfRendererPort);
    }

    @Test
    @DisplayName("tailorResume should throw AnalysisNotFoundException when job has not been analyzed")
    void tailorResume_whenNoAnalysis_shouldThrowAnalysisNotFound() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(AnalysisNotFoundException.class,
                () -> service.tailorResume(USER_ID, JOB_ID));
        verifyNoInteractions(aiPort, pdfRendererPort);
    }

    @Test
    @DisplayName("tailorResume should throw JobNotFoundException when job does not exist")
    void tailorResume_whenJobNotFound_shouldThrowJobNotFound() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class,
                () -> service.tailorResume(USER_ID, JOB_ID));
        verifyNoInteractions(aiPort, pdfRendererPort);
    }

    @Test
    @DisplayName("tailorResume should throw AiException when AI returns invalid JSON")
    void tailorResume_whenAiReturnsInvalidJson_shouldThrowAiException() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(aiPort.complete(anyString())).thenReturn("not json at all");

        assertThrows(AiException.class,
                () -> service.tailorResume(USER_ID, JOB_ID));
        verifyNoInteractions(pdfRendererPort);
    }

    @Test
    @DisplayName("tailorResume should throw AiException when AI call fails")
    void tailorResume_whenAiCallFails_shouldThrowAiException() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(aiPort.complete(anyString())).thenThrow(new RuntimeException("AI timeout"));

        var ex = assertThrows(AiException.class,
                () -> service.tailorResume(USER_ID, JOB_ID));
        assertTrue(ex.getMessage().contains("AI tailoring failed"));
        verifyNoInteractions(pdfRendererPort);
    }

    @Test
    @DisplayName("tailorResume should drop skills invented by the AI that are not in the original resume")
    void tailorResume_whenAiReturnsInventedSkill_shouldDropSkill() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        // "Kubernetes" is NOT in RESUME_TEXT -> must be dropped by the honesty guard
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(pdfRendererPort).renderPdf(htmlCaptor.capture());
        assertFalse(htmlCaptor.getValue().contains("Kubernetes"),
                "invented skill must not appear in the rendered resume");
        assertTrue(htmlCaptor.getValue().contains("PostgreSQL"),
                "real skill must remain in the rendered resume");
    }

    @Test
    @DisplayName("tailorResume should truncate resume text sent to the AI prompt when it exceeds max chars")
    void tailorResume_whenResumeTextTooLong_shouldTruncatePrompt() {
        newService(120);
        var longResume = RESUME_TEXT + "\n" + "x".repeat(500);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(new UserProfile(1L, USER_ID, longResume, List.of(), CompanyTone.FORMAL, List.of())));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(aiPort).complete(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("..."),
                "truncated prompt must end with an ellipsis marker");
        assertFalse(promptCaptor.getValue().contains("x".repeat(500)),
                "full resume text must not be sent when it exceeds max chars");
    }

    @Test
    @DisplayName("tailorResume should include job, analysis and resume data in the prompt")
    void tailorResume_whenValid_shouldIncludeJobAndAnalysisInPrompt() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(aiPort).complete(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("Desenvolvedor Java Júnior"));
        assertTrue(promptCaptor.getValue().contains("Java, Spring Boot"));
        assertTrue(promptCaptor.getValue().contains("Kubernetes"));
        assertTrue(promptCaptor.getValue().contains("HABILIDADES TÉCNICAS"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Job job() {
        return new Job(JOB_ID, "Desenvolvedor Java Júnior", "Acme",
                "https://acme.com/jobs/10", "Vaga Java com Spring Boot e PostgreSQL", LocalDate.now(), "gupy");
    }

    private JobAnalysis analysis() {
        return new JobAnalysis(1L, JOB_ID, USER_ID, 75,
                List.of("Java", "Spring Boot"), List.of("Kubernetes"),
                CompanyTone.FORMAL, "Backend Java role");
    }

    private UserProfile profile() {
        return new UserProfile(1L, USER_ID, RESUME_TEXT,
                List.of("Java", "Spring Boot", "PostgreSQL"), CompanyTone.FORMAL, List.of());
    }

    private String validAiJson() {
        return """
                {
                  "objective": "Desenvolvedor Java Júnior com foco em Spring Boot",
                  "skills": {
                    "languages": ["Java"],
                    "frameworks": ["Spring Boot"],
                    "databases": ["PostgreSQL"],
                    "cloudDevOps": ["Kubernetes"],
                    "tools": [],
                    "concepts": []
                  },
                  "projects": [
                    {"name": "Job Hunter", "bullets": ["Automação de candidaturas com IA"], "link": "https://github.com/Uzzoper/job-hunter"}
                  ],
                  "experience": [
                    {"role": "Analista de Frota Pleno", "company": "BLD Logística", "period": "2026 – atual", "bullets": ["Gestão de frota regional"]}
                  ],
                  "education": [
                    {"degree": "Engenharia de Software", "institution": "Unicesumar", "status": "em andamento"}
                  ],
                  "courses": ["Oracle Java Foundations"],
                  "languages": [
                    {"language": "Português", "level": "Nativo"}
                  ],
                  "differentials": ["Perfil autodidata"]
                }
                """;
    }
}
