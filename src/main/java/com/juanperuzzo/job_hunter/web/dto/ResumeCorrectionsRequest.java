package com.juanperuzzo.job_hunter.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Optional chat corrections carried by the commit call (issue #92 slice b),
 * as a JSON multipart part. Merge order: chat correction &gt; stored value &gt;
 * AI extraction. {@code digest}, when present, must match the uploaded file's
 * md5 — otherwise the commit answers {@code 409} and nothing is written.
 */
public record ResumeCorrectionsRequest(
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Email(message = "email must be a valid email address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @Size(max = 30, message = "phone must be at most 30 characters")
        String phone,

        @Email(message = "contactEmail must be a valid email address")
        @Size(max = 255, message = "contactEmail must be at most 255 characters")
        String contactEmail,

        @Size(max = 500, message = "portfolioUrl must be at most 500 characters")
        String portfolioUrl,

        String digest
) {}
