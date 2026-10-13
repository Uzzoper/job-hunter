package com.juanperuzzo.job_hunter.application.port.out;

import java.util.List;

public interface ScraperPort {

    /**
     * Fetch and normalize jobs from all registered providers.
     *
     * @return the normalized jobs plus raw per-provider fetch statistics
     */
    ScraperResult fetch(List<String> keywordOverride);

    default ScraperResult fetch() {
        return fetch(List.of());
    }
}