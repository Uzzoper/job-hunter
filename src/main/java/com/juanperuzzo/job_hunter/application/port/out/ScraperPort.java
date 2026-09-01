package com.juanperuzzo.job_hunter.application.port.out;

public interface ScraperPort {

    /**
     * Fetch and normalize jobs from all registered providers.
     *
     * @return the normalized jobs plus raw per-provider fetch statistics
     */
    ScraperResult fetch();
}