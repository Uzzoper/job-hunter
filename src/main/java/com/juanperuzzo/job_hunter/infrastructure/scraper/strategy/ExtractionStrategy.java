package com.juanperuzzo.job_hunter.infrastructure.scraper.strategy;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;

import java.util.List;

public interface ExtractionStrategy {
    String providerId();
    List<RawJob> extract();

    /**
     * Keyword-aware extraction. Default ignores the override (non-keyword providers);
     * keyword providers override this to resolve the effective list at fetch time.
     */
    default List<RawJob> extract(List<String> keywordOverride) {
        return extract();
    }
}
