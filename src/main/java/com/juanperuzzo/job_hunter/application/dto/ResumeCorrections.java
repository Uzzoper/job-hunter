package com.juanperuzzo.job_hunter.application.dto;

/**
 * Optional chat corrections applied at commit time (issue #92 slice b).
 * Merge order: chat correction &gt; stored value &gt; AI extraction — a chat
 * correction is an explicit user instruction and overrides everything;
 * extraction keeps losing to stored values (fill-if-empty preserved).
 *
 * <p>{@code digest}, when present, must match the uploaded file's md5 — the
 * confirmed artifact is provably the previewed one.
 *
 * @param name          operator display name correction (profile)
 * @param email         operator email correction (profile)
 * @param phone         contact correction
 * @param contactEmail  contact correction
 * @param portfolioUrl  contact correction
 * @param digest        md5 echo of the previewed artifact
 */
public record ResumeCorrections(
        String name,
        String email,
        String phone,
        String contactEmail,
        String portfolioUrl,
        String digest
) {}
