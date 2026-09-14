package com.juanperuzzo.job_hunter.application.service;

import com.juanperuzzo.job_hunter.domain.model.Project;
import com.juanperuzzo.job_hunter.domain.model.User;
import com.juanperuzzo.job_hunter.domain.model.UserProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Resolves the identity and contact placeholders of an application email from the
 * candidate's {@link User} and {@link UserProfile}. Pure static functions — no
 * Spring, no repositories, no I/O.
 *
 * <p>Syntax follows the resume-template convention: double braces and
 * {@code UPPER_SNAKE_CASE}, e.g. {@code {{CANDIDATE_NAME}}},
 * {@code {{SKILLS}}}, {@code {{PROJECTS}}}.</p>
 *
 * <p>Global rule: a rendered email must never contain a raw {@code {{...}}}
 * token. Unresolvable placeholders drop their whole line (or block, for
 * {@code {{PROJECTS}}}).</p>
 */
public final class ProfilePlaceholders {

    private ProfilePlaceholders() {
    }

    /**
     * Builds the map of placeholder name ({@code CANDIDATE_NAME}, {@code PHONE}, …)
     * to its resolved value for the given user and profile. Fields that are null,
     * blank, or empty (skills list / projects list) are simply absent from the map.
     */
    public static ResolvedPlaceholders resolve(User user, UserProfile profile) {
        var values = new LinkedHashMap<String, String>();
        putIfPresent(values, "CANDIDATE_NAME", user.name());
        putIfPresent(values, "CANDIDATE_EMAIL", user.email());
        putIfPresent(values, "PHONE", profile.phone());
        putIfPresent(values, "PORTFOLIO_URL", profile.portfolioUrl());
        putIfPresent(values, "GITHUB_URL", profile.githubUrl());
        putIfPresent(values, "LINKEDIN_URL", profile.linkedinUrl());
        putIfPresent(values, "SKILLS", renderSkills(profile.skills()));
        putIfPresent(values, "PROJECTS", renderProjects(profile.projects()));
        return new ResolvedPlaceholders(Map.copyOf(values));
    }

    /**
     * Resolves the placeholders in {@code template} line by line. Lines that still
     * hold an unresolvable {@code {{...}}} token after substitution are dropped
     * entirely, so the output never leaks a raw placeholder.
     */
    public static String resolve(String template, User user, UserProfile profile) {
        ResolvedPlaceholders placeholders = resolve(user, profile);
        return template.lines()
                .map(line -> resolveLine(line, placeholders))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Renders the {@code CANDIDATE FACTS} block used by the AI generation prompt
     * from the same resolver output as the standard-template email.
     */
    public static String factsBlock(User user, UserProfile profile) {
        return factsBlock(resolve(user, profile));
    }

    /**
     * Renders the {@code CANDIDATE FACTS} block from already-resolved values.
     */
    public static String factsBlock(ResolvedPlaceholders placeholders) {
        var values = placeholders.values();
        var sb = new StringBuilder("CANDIDATE FACTS:");
        appendFact(sb, values, "CANDIDATE_NAME", "Name");
        appendFact(sb, values, "CANDIDATE_EMAIL", "Email");
        appendFact(sb, values, "PHONE", "Phone");
        appendFact(sb, values, "PORTFOLIO_URL", "Portfolio");
        appendFact(sb, values, "GITHUB_URL", "GitHub");
        appendFact(sb, values, "LINKEDIN_URL", "LinkedIn");
        appendFact(sb, values, "SKILLS", "Skills");
        var projects = values.get("PROJECTS");
        if (projects != null) {
            sb.append("\n- Projects:");
            for (var projectLine : projects.split("\n")) {
                sb.append("\n  ").append(projectLine);
            }
        }
        return sb.toString();
    }

    private static String resolveLine(String line, ResolvedPlaceholders placeholders) {
        if (!line.contains("{{")) {
            return line;
        }
        String resolved = line;
        for (var entry : placeholders.values().entrySet()) {
            resolved = resolved.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return resolved.contains("{{") || resolved.contains("}}") ? null : resolved;
    }

    private static void putIfPresent(Map<String, String> values, String placeholder, String value) {
        if (value != null && !value.isBlank()) {
            values.put(placeholder, value.trim());
        }
    }

    private static String renderSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        return String.join(", ", skills);
    }

    private static String renderProjects(List<Project> projects) {
        if (projects == null || projects.isEmpty()) {
            return null;
        }
        return projects.stream()
                .map(p -> "• " + p.name() + " — " + p.description() + " (" + p.techStack() + ")")
                .collect(Collectors.joining("\n"));
    }

    private static void appendFact(StringBuilder sb, Map<String, String> values, String placeholder, String label) {
        var value = values.get(placeholder);
        if (value != null) {
            sb.append("\n- ").append(label).append(": ").append(value);
        }
    }
}