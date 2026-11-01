package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import java.util.Map;

/**
 * HTTP headers shared by the ATS board providers (Greenhouse/Ashby/Lever).
 * ATS edges reject bare default Java/python User-Agents with HTTP 403 (probed
 * 2026-10-23: Ashby confirmed), so all three send the honest {@code JobHunter/1.0}
 * UA instead.
 */
public final class AtsHttpHeaders {

    /** Honest, shared User-Agent headers for every ATS board provider. */
    public static final Map<String, String> DEFAULT = Map.of("User-Agent", "JobHunter/1.0");

    private AtsHttpHeaders() {
    }
}
