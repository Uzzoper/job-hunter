package com.juanperuzzo.job_hunter.application.port.out;

/**
 * Outbound port for contact-email extraction, decoupling the application layer from the
 * shared infrastructure pipeline ({@code EmailExtractor}) while keeping the backfill use
 * case on the exact {@code extract(title, description)} contract used at ingestion time.
 */
public interface ContactEmailExtractorPort {

    /**
     * Best-effort extraction of a contact email from a job title and/or description.
     *
     * @param title       job title (may be {@code null} or blank)
     * @param description job description (may be {@code null} or blank)
     * @return the winning contact email, or {@code null} when none qualifies
     */
    String extract(String title, String description);
}