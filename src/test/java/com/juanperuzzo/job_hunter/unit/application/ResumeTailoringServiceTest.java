package com.juanperuzzo.job_hunter.unit.application;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.PdfRendererPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.service.ResumeTailoringService;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.AnalysisNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.User;
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
    private UserRepository userRepository;

    @Mock
    private PdfRendererPort pdfRendererPort;

    @Captor
    private ArgumentCaptor<String> promptCaptor;

    @Captor
    private ArgumentCaptor<String> htmlCaptor;

    private ResumeTailoringService service;

    private void newService(int maxAiChars) {
        service = new ResumeTailoringService(aiPort, jobRepository, jobAnalysisRepository,
                userProfileRepository, userRepository, pdfRendererPort, maxAiChars);
    }

    @Test
    @DisplayName("tailorResume should return PDF bytes when job, analysis and profile exist")
    void tailorResume_whenValid_shouldReturnPdfBytes() {
        newService(8000);
        var pdfBytes = new byte[]{37, 80, 68, 70, 1, 2, 3};

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
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
                .thenReturn(Optional.of(new UserProfile(1L, USER_ID, "   ", List.of(), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null)));

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
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
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
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
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
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
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
                .thenReturn(Optional.of(new UserProfile(1L, USER_ID, longResume, List.of(), CompanyTone.FORMAL, List.of(),
                        null, null, null, null, null)));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
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
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(aiPort).complete(promptCaptor.capture());
        assertTrue(promptCaptor.getValue().contains("Desenvolvedor Java Júnior"));
        assertTrue(promptCaptor.getValue().contains("Java, Spring Boot"));
        assertTrue(promptCaptor.getValue().contains("Kubernetes"));
        assertTrue(promptCaptor.getValue().contains("HABILIDADES TÉCNICAS"));
    }

    @Test
    @DisplayName("tailorResume should render project links as clickable anchors")
    void tailorResume_whenProjectHasLink_shouldRenderClickableLink() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(pdfRendererPort).renderPdf(htmlCaptor.capture());
        assertTrue(htmlCaptor.getValue().contains("<a href=\"https://github.com/Uzzoper/job-hunter\">"),
                "project link must be rendered as a clickable anchor");
    }

    @Test
    @DisplayName("tailorResume should not render non-http links as clickable anchors")
    void tailorResume_whenProjectLinkIsNotHttp_shouldNotRenderClickable() {
        newService(8000);
        var aiJson = validAiJson().replace("https://github.com/Uzzoper/job-hunter", "javascript:alert(1)");

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(aiPort.complete(anyString())).thenReturn(aiJson);
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(pdfRendererPort).renderPdf(htmlCaptor.capture());
        assertFalse(htmlCaptor.getValue().contains("<a href=\"javascript:"),
                "non-http link must not be rendered as a clickable anchor");
        assertTrue(htmlCaptor.getValue().contains("javascript:alert(1)"),
                "non-http link must still appear as plain text");
    }

    @Test
    @DisplayName("tailorResume should render user name and contact links from profile")
    void tailorResume_shouldRenderUserNameAndContactLinksFromProfile() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profileWithContact()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(pdfRendererPort).renderPdf(htmlCaptor.capture());
        var html = htmlCaptor.getValue();
        assertTrue(html.contains("<h1>Juan Antonio Peruzzo</h1>"),
                "h1 must contain the registered user name");
        assertTrue(html.contains("<a href=\"mailto:contato@juan.dev\">contato@juan.dev</a>"),
                "email anchor must use the profile contact email");
        assertTrue(html.contains("<a href=\"https://juanperuzzo.is-a.dev\">juanperuzzo.is-a.dev</a>"),
                "portfolio must be clickable with protocol-stripped display text");
        assertTrue(html.contains("<a href=\"https://github.com/Uzzoper\">github.com/Uzzoper</a>"),
                "github must be clickable with protocol-stripped display text");
        assertTrue(html.contains("<a href=\"https://linkedin.com/in/juanperuzzo\">linkedin.com/in/juanperuzzo</a>"),
                "linkedin must be clickable with protocol-stripped display text");
        assertTrue(html.contains("(42) 99833-1363"),
                "phone must be rendered as plain text");
    }

    @Test
    @DisplayName("tailorResume should fall back to registered email and omit empty contact fields")
    void tailorResume_whenContactFieldsMissing_shouldFallbackToRegisteredEmail() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(aiPort.complete(anyString())).thenReturn(validAiJson());
        when(pdfRendererPort.renderPdf(anyString())).thenReturn(new byte[]{1});

        service.tailorResume(USER_ID, JOB_ID);

        verify(pdfRendererPort).renderPdf(htmlCaptor.capture());
        var html = htmlCaptor.getValue();
        assertTrue(html.contains(
                        "<p class=\"contact\"><a href=\"mailto:juan@example.com\">juan@example.com</a></p>"),
                "contact line must contain only the fallback email with no dangling separators");
        assertFalse(html.contains("href=\"https://github.com/Uzzoper\""),
                "empty profile github must not render a contact anchor");
        assertFalse(html.contains("linkedin.com/in/juanperuzzo"),
                "empty linkedin must not render");
        assertFalse(html.contains("juanperuzzo.is-a.dev"),
                "empty portfolio must not render");
        assertFalse(html.contains("(42) 99833-1363"),
                "empty phone must not render");
    }

    @Test
    @DisplayName("tailorResume should throw UserNotFoundException when user does not exist")
    void tailorResume_whenUserNotFound_shouldThrowUserNotFound() {
        newService(8000);

        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(jobAnalysisRepository.findByJobIdAndUserId(JOB_ID, USER_ID)).thenReturn(Optional.of(analysis()));
        when(userProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile()));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service.tailorResume(USER_ID, JOB_ID));
        verifyNoInteractions(aiPort, pdfRendererPort);
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
                List.of("Java", "Spring Boot", "PostgreSQL"), CompanyTone.FORMAL, List.of(),
                null, null, null, null, null);
    }

    private UserProfile profileWithContact() {
        return new UserProfile(1L, USER_ID, RESUME_TEXT,
                List.of("Java", "Spring Boot", "PostgreSQL"), CompanyTone.FORMAL, List.of(),
                "(42) 99833-1363", "contato@juan.dev",
                "https://juanperuzzo.is-a.dev", "https://github.com/Uzzoper",
                "https://linkedin.com/in/juanperuzzo");
    }

    private User user() {
        return new User(USER_ID, "juan@example.com", "Juan Antonio Peruzzo", "$2a$hash");
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
