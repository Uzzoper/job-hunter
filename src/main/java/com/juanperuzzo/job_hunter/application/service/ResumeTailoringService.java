package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.PdfRendererPort;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.AnalysisNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.JobNotFoundException;
import com.juanperuzzo.job_hunter.domain.exception.ProfileNotConfiguredException;
import com.juanperuzzo.job_hunter.domain.exception.UserNotFoundException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.domain.model.JobAnalysis;
import com.juanperuzzo.job_hunter.domain.model.TailoredResume;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tailors the user's resume for a specific job using AI (Prompt 4), applies an
 * honesty guard that drops any skill not present in the original resume text,
 * fills the static ATS template, and renders the result as PDF bytes.
 *
 * <p>Identity and contact data are dynamic (spec v1.1): the candidate name comes
 * from the authenticated {@link User} record and the contact line is built from
 * the user's own {@link UserProfile} contact fields — never hardcoded.</p>
 */
public class ResumeTailoringService implements TailorResumeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ResumeTailoringService.class);
    private static final String TEMPLATE_PATH = "resume/ats-template.html";
    private static final String CONTACT_SEPARATOR = " &#8226; ";

    private final AiPort aiPort;
    private final JobRepository jobRepository;
    private final JobAnalysisRepository jobAnalysisRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserRepository userRepository;
    private final PdfRendererPort pdfRendererPort;
    private final int maxAiChars;
    private final ObjectMapper objectMapper;
    private final String template;

    /**
     * @param aiPort                 port for AI completions
     * @param jobRepository          repository for loading the target job
     * @param jobAnalysisRepository  repository for loading the job analysis (matched/missing skills)
     * @param userProfileRepository  repository for loading the user's resume text
     * @param userRepository         repository for loading the user identity (name, registered email)
     * @param pdfRendererPort        port for rendering the filled HTML template to PDF
     * @param maxAiChars             maximum character count of resume text sent to the AI prompt
     */
    public ResumeTailoringService(AiPort aiPort, JobRepository jobRepository,
                                  JobAnalysisRepository jobAnalysisRepository,
                                  UserProfileRepository userProfileRepository,
                                  UserRepository userRepository,
                                  PdfRendererPort pdfRendererPort,
                                  int maxAiChars) {
        this.aiPort = aiPort;
        this.jobRepository = jobRepository;
        this.jobAnalysisRepository = jobAnalysisRepository;
        this.userProfileRepository = userProfileRepository;
        this.userRepository = userRepository;
        this.pdfRendererPort = pdfRendererPort;
        this.maxAiChars = maxAiChars;
        this.objectMapper = new ObjectMapper();
        this.template = loadTemplate();
    }

    /**
     * Generates a tailored resume PDF for the given user and job.
     *
     * @param userId the authenticated user's ID
     * @param jobId  the job the resume is tailored for
     * @return the tailored resume as PDF bytes
     * @throws JobNotFoundException          if the job does not exist
     * @throws AnalysisNotFoundException     if the job has not been analyzed for the user
     * @throws ProfileNotConfiguredException if the user has no profile or blank resume text
     * @throws UserNotFoundException         if the user record no longer exists
     * @throws AiException                   if the AI call fails or returns invalid JSON
     */
    @Override
    public byte[] tailorResume(Long userId, Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found with id: " + jobId));

        JobAnalysis analysis = jobAnalysisRepository.findByJobIdAndUserId(jobId, userId)
                .orElseThrow(() -> new AnalysisNotFoundException("Job must be analyzed before tailoring the resume"));

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ProfileNotConfiguredException("Please configure your resume and skills profile first"));

        if (profile.resumeText() == null || profile.resumeText().isBlank()) {
            throw new ProfileNotConfiguredException("Please configure your resume and skills profile first");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        String prompt = buildPrompt(job, analysis, profile);
        String response;
        try {
            response = aiPort.complete(prompt);
        } catch (Exception e) {
            throw new AiException("AI tailoring failed: " + e.getMessage(), e);
        }

        TailoredResume tailored = parseTailoredResume(response, profile.resumeText());

        String html = fillTemplate(tailored, user, profile);
        return pdfRendererPort.renderPdf(html);
    }

    private String buildPrompt(Job job, JobAnalysis analysis, UserProfile profile) {
        var textForAi = profile.resumeText();
        if (textForAi.length() > maxAiChars) {
            log.warn("Resume text is {} chars, truncating to {} for AI prompt", textForAi.length(), maxAiChars);
            textForAi = textForAi.substring(0, maxAiChars) + "...";
        }
        return """
You are a career assistant that tailors resumes for Applicant Tracking Systems (ATS).

Rewrite the resume below for the target job. Keep the EXACT same sections and structure.
Return ONLY a valid JSON object, with no markdown and no additional text.

Response format:
{
  "objective": "<2-3 lines, keyword-rich, tailored to the role>",
  "skills": {
    "languages": ["skill 1", "..."],
    "frameworks": ["skill 1", "..."],
    "databases": ["skill 1", "..."],
    "cloudDevOps": ["skill 1", "..."],
    "tools": ["skill 1", "..."],
    "concepts": ["skill 1", "..."]
  },
  "projects": [
    {
      "name": "<project name>",
      "bullets": ["<bullet 1>", "..."],
      "link": "<url or empty string>"
    }
  ],
  "experience": [
    {
      "role": "<role>",
      "company": "<company>",
      "period": "<period>",
      "bullets": ["<bullet 1>", "..."]
    }
  ],
  "education": [
    { "degree": "<degree>", "institution": "<institution>", "status": "<status>" }
  ],
  "courses": ["<course 1>", "..."],
  "languages": [
    { "language": "<language>", "level": "<level>" }
  ],
  "differentials": ["<differential 1>", "..."]
}

MANDATORY RULES:
1. NEVER invent skills, companies, roles, dates, degrees, courses, projects, or links.
   Only reorder, rephrase, and emphasize content that already exists in the resume.
2. Use the EXACT skill names from the resume (do not rename or rephrase skills).
3. Within each skills group, put the skills the candidate has for this role FIRST,
   then the remaining skills in their original order.
4. Rewrite bullets to include keywords from the job description ONLY when those
   keywords truthfully describe existing content. Never stretch the truth.
5. Reorder projects by relevance to the job (most relevant first). Keep all projects.
6. Keep the same language as the original resume.
7. Keep all sections. Education, courses, and languages are passed through unchanged.
8. The objective must mention the target role and the candidate's strongest relevant
   skills, without inventing anything.

Candidate skills for this role: %s
Skills the candidate lacks (do NOT add them to the resume): %s

Job listing:
Title: %s
Company: %s
Description: %s

Resume text:
%s
""".formatted(
                String.join(", ", analysis.matchedSkills()),
                String.join(", ", analysis.missingSkills()),
                job.title(),
                job.company(),
                job.description(),
                textForAi);
    }

    private TailoredResume parseTailoredResume(String response, String resumeText) {
        try {
            String cleaned = response.strip();
            cleaned = cleaned.replaceAll("```[a-zA-Z]*\\s*|```\\s*", "").strip();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1) {
                throw new AiException("AI response contains no valid JSON");
            }
            String json = cleaned.substring(start, end + 1);
            var root = objectMapper.readTree(json);

            String objective = root.path("objective").asText("");

            var skillsNode = root.path("skills");
            var skills = new TailoredResume.Skills(
                    filterSkills(skillsNode.path("languages"), resumeText),
                    filterSkills(skillsNode.path("frameworks"), resumeText),
                    filterSkills(skillsNode.path("databases"), resumeText),
                    filterSkills(skillsNode.path("cloudDevOps"), resumeText),
                    filterSkills(skillsNode.path("tools"), resumeText),
                    filterSkills(skillsNode.path("concepts"), resumeText)
            );

            return new TailoredResume(
                    objective,
                    skills,
                    parseProjects(root.path("projects")),
                    parseExperience(root.path("experience")),
                    parseEducation(root.path("education")),
                    parseStringList(root.path("courses")),
                    parseLanguages(root.path("languages")),
                    parseStringList(root.path("differentials"))
            );
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse AI tailoring response. Raw: {}", response, e);
            throw new AiException("Failed to parse AI tailoring response: " + e.getMessage(), e);
        }
    }

    /**
     * Honesty guard: drops any skill that does not appear in the original resume
     * text (case-insensitive substring check), so the AI can never fabricate skills.
     */
    private List<String> filterSkills(JsonNode node, String resumeText) {
        var result = new ArrayList<String>();
        if (node == null || !node.isArray()) {
            return result;
        }
        String lowerResume = resumeText.toLowerCase();
        for (var skill : node) {
            String s = skill.asText("").trim();
            if (s.isEmpty()) {
                continue;
            }
            if (lowerResume.contains(s.toLowerCase())) {
                result.add(s);
            } else {
                log.warn("Dropping invented skill not present in resume: {}", s);
            }
        }
        return result;
    }

    private List<TailoredResume.TailoredProject> parseProjects(JsonNode node) {
        var result = new ArrayList<TailoredResume.TailoredProject>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (var p : node) {
            result.add(new TailoredResume.TailoredProject(
                    p.path("name").asText(""),
                    parseStringList(p.path("bullets")),
                    p.path("link").asText("")
            ));
        }
        return result;
    }

    private List<TailoredResume.TailoredExperience> parseExperience(JsonNode node) {
        var result = new ArrayList<TailoredResume.TailoredExperience>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (var e : node) {
            result.add(new TailoredResume.TailoredExperience(
                    e.path("role").asText(""),
                    e.path("company").asText(""),
                    e.path("period").asText(""),
                    parseStringList(e.path("bullets"))
            ));
        }
        return result;
    }

    private List<TailoredResume.TailoredEducation> parseEducation(JsonNode node) {
        var result = new ArrayList<TailoredResume.TailoredEducation>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (var e : node) {
            result.add(new TailoredResume.TailoredEducation(
                    e.path("degree").asText(""),
                    e.path("institution").asText(""),
                    e.path("status").asText("")
            ));
        }
        return result;
    }

    private List<TailoredResume.TailoredLanguage> parseLanguages(JsonNode node) {
        var result = new ArrayList<TailoredResume.TailoredLanguage>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (var l : node) {
            result.add(new TailoredResume.TailoredLanguage(
                    l.path("language").asText(""),
                    l.path("level").asText("")
            ));
        }
        return result;
    }

    private List<String> parseStringList(JsonNode node) {
        var result = new ArrayList<String>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (var item : node) {
            result.add(item.asText(""));
        }
        return result;
    }

    private String fillTemplate(TailoredResume tailored, User user, UserProfile profile) {
        String html = template;
        html = html.replace("{{FULL_NAME}}", escapeHtml(user.name()));
        html = html.replace("{{CONTACT}}", renderContact(profile, user));
        html = html.replace("{{OBJECTIVE}}", renderObjective(tailored.objective()));
        html = html.replace("{{SKILLS_GROUPS}}", renderSkillsGroups(tailored.skills()));
        html = html.replace("{{PROJECTS}}", renderProjects(tailored.projects()));
        html = html.replace("{{EXPERIENCE}}", renderExperience(tailored.experience()));
        html = html.replace("{{EDUCATION}}", renderEducation(tailored.education()));
        html = html.replace("{{COURSES}}", renderSimpleList(tailored.courses()));
        html = html.replace("{{LANGUAGES}}", renderLanguages(tailored.languages()));
        html = html.replace("{{DIFFERENTIALS}}", renderSimpleList(tailored.differentials()));
        return html;
    }

    /**
     * Builds the contact line from the user's own profile fields (spec v1.1):
     * phone as plain text, then email, portfolio, GitHub and LinkedIn anchors.
     * Empty parts are skipped so no dangling separators are rendered; the
     * registered account email is the fallback for the mailto anchor.
     */
    private String renderContact(UserProfile profile, User user) {
        var parts = new ArrayList<String>();
        if (isNotBlank(profile.phone())) {
            parts.add(escapeHtml(profile.phone().trim()));
        }
        String email = isNotBlank(profile.contactEmail()) ? profile.contactEmail().trim() : user.email();
        parts.add("<a href=\"mailto:" + escapeHtml(email) + "\">" + escapeHtml(email) + "</a>");
        if (isNotBlank(profile.portfolioUrl())) {
            parts.add(renderContactLink(profile.portfolioUrl()));
        }
        if (isNotBlank(profile.githubUrl())) {
            parts.add(renderContactLink(profile.githubUrl()));
        }
        if (isNotBlank(profile.linkedinUrl())) {
            parts.add(renderContactLink(profile.linkedinUrl()));
        }
        return String.join(CONTACT_SEPARATOR, parts);
    }

    /**
     * Renders a contact URL reusing {@link #renderLink(String)} for href safety,
     * with the display text stripped of the leading http(s):// prefix
     * (e.g. {@code https://github.com/Uzzoper} displays as {@code github.com/Uzzoper}).
     */
    private String renderContactLink(String url) {
        String trimmed = url.trim();
        return renderLink(trimmed, stripProtocol(trimmed));
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String stripProtocol(String url) {
        if (url.startsWith("https://")) {
            return url.substring("https://".length());
        }
        if (url.startsWith("http://")) {
            return url.substring("http://".length());
        }
        return url;
    }

    private String renderObjective(String objective) {
        return "<p>" + escapeHtml(objective) + "</p>";
    }

    private String renderSkillsGroups(TailoredResume.Skills skills) {
        var sb = new StringBuilder();
        appendSkillGroup(sb, "Linguagens", skills.languages());
        appendSkillGroup(sb, "Frameworks &amp; Libs", skills.frameworks());
        appendSkillGroup(sb, "Banco de Dados", skills.databases());
        appendSkillGroup(sb, "Cloud &amp; DevOps", skills.cloudDevOps());
        appendSkillGroup(sb, "Ferramentas", skills.tools());
        appendSkillGroup(sb, "Conceitos", skills.concepts());
        return sb.toString();
    }

    private void appendSkillGroup(StringBuilder sb, String label, List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        sb.append("<p>").append(label).append(": ").append(String.join(", ", skills)).append("</p>");
    }

    private String renderProjects(List<TailoredResume.TailoredProject> projects) {
        var sb = new StringBuilder();
        for (var p : projects) {
            sb.append("<p class=\"project-title\"><strong>").append(escapeHtml(p.name())).append("</strong></p>");
            if (p.bullets() != null && !p.bullets().isEmpty()) {
                sb.append("<ul>");
                for (var bullet : p.bullets()) {
                    sb.append("<li>").append(escapeHtml(bullet)).append("</li>");
                }
                sb.append("</ul>");
            }
            if (p.link() != null && !p.link().isBlank()) {
                sb.append("<p class=\"project-link\">").append(renderLink(p.link())).append("</p>");
            }
        }
        return sb.toString();
    }

    /**
     * Renders a link as a clickable anchor when it is a safe http(s) URL;
     * any other value is rendered as plain text (never as a clickable link).
     */
    private String renderLink(String url) {
        return renderLink(url, url.trim());
    }

    /**
     * Shared href-safety logic: only http(s) URLs become anchors, everything
     * else is escaped plain text. The display text is provided by the caller.
     */
    private String renderLink(String url, String displayText) {
        String trimmed = url.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return "<a href=\"" + escapeHtml(trimmed) + "\">" + escapeHtml(displayText) + "</a>";
        }
        return escapeHtml(trimmed);
    }

    private String renderExperience(List<TailoredResume.TailoredExperience> experience) {
        var sb = new StringBuilder();
        for (var e : experience) {
            sb.append("<p class=\"experience-header\"><strong>").append(escapeHtml(e.role())).append("</strong> &#8212; ")
                    .append(escapeHtml(e.company())).append(" &#160;&#160;&#160; ")
                    .append(escapeHtml(e.period())).append("</p>");
            if (e.bullets() != null && !e.bullets().isEmpty()) {
                sb.append("<ul>");
                for (var bullet : e.bullets()) {
                    sb.append("<li>").append(escapeHtml(bullet)).append("</li>");
                }
                sb.append("</ul>");
            }
        }
        return sb.toString();
    }

    private String renderEducation(List<TailoredResume.TailoredEducation> education) {
        var sb = new StringBuilder();
        for (var e : education) {
            sb.append("<p class=\"education-line\">").append(escapeHtml(e.degree())).append(" &#8212; ")
                    .append(escapeHtml(e.institution())).append(" &#160;&#160;&#160; ")
                    .append(escapeHtml(e.status())).append("</p>");
        }
        return sb.toString();
    }

    private String renderLanguages(List<TailoredResume.TailoredLanguage> languages) {
        var sb = new StringBuilder("<ul>");
        for (var l : languages) {
            sb.append("<li>").append(escapeHtml(l.language())).append(": ").append(escapeHtml(l.level())).append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private String renderSimpleList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder("<ul>");
        for (var item : items) {
            sb.append("<li>").append(escapeHtml(item)).append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String loadTemplate() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Template not found in classpath: " + TEMPLATE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load resume template", e);
        }
    }
}