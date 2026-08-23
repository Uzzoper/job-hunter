package com.juanperuzzo.job_hunter.infrastructure.scraper.strategy;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.parser.JsonLdParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class HtmlStrategy implements ExtractionStrategy {

    private final String providerId;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final JsonLdParser jsonLdParser;
    private final Function<Document, List<RawJob>> htmlMapper;

    public HtmlStrategy(
            String providerId,
            String baseUrl,
            int timeoutSeconds,
            JsonLdParser jsonLdParser,
            Function<Document, List<RawJob>> htmlMapper) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.jsonLdParser = jsonLdParser;
        this.htmlMapper = htmlMapper;
    }

    public HtmlStrategy(
            String providerId,
            String baseUrl,
            int timeoutSeconds,
            JsonLdParser jsonLdParser) {
        this(providerId, baseUrl, timeoutSeconds, jsonLdParser, null);
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        return extractFromUrl(baseUrl);
    }

    public List<RawJob> extractFromUrl(String url) {
        try {
            var doc = Jsoup.parse(new URI(url).toURL(), timeoutSeconds * 1000);

            var results = new ArrayList<RawJob>();

            if (htmlMapper != null) {
                var mapped = htmlMapper.apply(doc);
                if (mapped != null) {
                    results.addAll(mapped);
                }
            }

            jsonLdParser.parseFromDocument(doc).ifPresent(ld -> {
                results.add(new RawJob(
                        ld.title(),
                        ld.company(),
                        "",  // URL not available from JSON-LD
                        ld.description(),
                        ld.datePosted(),
                        ld.location(),
                        ld.workModel(),
                        providerId,
                        null));
            });

            return results;

        } catch (Exception e) {
            throw new ScraperException(providerId + " HTML extraction failed: " + e.getMessage(), e);
        }
    }

    public Optional<String> textFrom(Element root, String selector) {
        return Optional.ofNullable(root.selectFirst(selector))
                .map(Element::text)
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }

    public Optional<String> attrFrom(Element root, String selector, String attribute) {
        return Optional.ofNullable(root.selectFirst(selector))
                .map(el -> el.attr(attribute))
                .map(String::trim)
                .filter(s -> !s.isEmpty());
    }
}
