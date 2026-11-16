package com.juanperuzzo.job_hunter.application.dto;

import com.juanperuzzo.job_hunter.application.port.in.AccountIdentity;
import com.juanperuzzo.job_hunter.domain.model.CompanyTone;
import java.util.List;

/**
 * Stateless resume preview (issue #92 slice b): what a commit would write,
 * computed against the stored profile — persists nothing. The bot summarizes
 * these facts in chat and waits for an explicit confirmation before calling
 * the commit endpoint.
 *
 * @param accountIdentity the users row (name + login email) — slice (a) source of truth
 * @param contact         fill-if-empty merge preview (stored value wins over extraction)
 * @param skills          extracted skills
 * @param projects        extracted projects
 * @param tone            the tone the commit would keep (stored, else FORMAL)
 * @param resumeTextChars extracted resume text length
 * @param digest          md5 of the uploaded bytes — echoed back at commit
 * @param missingFields   contact fields still empty after the merge preview, for the bot to ask in chat
 */
public record ResumePreview(
        AccountIdentity accountIdentity,
        ContactPreview contact,
        List<String> skills,
        List<ProjectPreview> projects,
        CompanyTone tone,
        int resumeTextChars,
        String digest,
        List<String> missingFields
) {
    /** Contact fields the chat flow confirms; the merge preview is fill-if-empty. */
    public record ContactPreview(String phone, String contactEmail, String portfolioUrl) {}

    public record ProjectPreview(String name, String description, List<String> techStack) {}
}
