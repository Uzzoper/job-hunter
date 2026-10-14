package com.juanperuzzo.job_hunter.infrastructure.scraper.strategy;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;

import java.util.List;
import java.util.function.Predicate;

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

    /**
     * Keyword-aware, early-dedupe-aware extraction. Default ignores both the override
     * and the predicate (non-keyword and non-enriching providers); enrichment sites
     * override this to skip their detail HTTP call when {@code isKnownUrl} reports the
     * job URL is already persisted, still returning the basic (unenriched) job.
     */
    default List<RawJob> extract(List<String> keywordOverride, Predicate<String> isKnownUrl) {
        return extract(keywordOverride);
    }
}
