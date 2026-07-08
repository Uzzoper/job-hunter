package com.juanperuzzo.job_hunter.infrastructure.scraper.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class JsonLdParser {

    private static final Logger log = LoggerFactory.getLogger(JsonLdParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record JsonLdJobPosting(
            String title,
            String description,
            String company,
            String datePosted,
            String location,
            String employmentType,
            String workModel
    ) {}

    public Optional<JsonLdJobPosting> parse(String html) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }
        return parseFromDocument(Jsoup.parse(html));
    }

    public Optional<JsonLdJobPosting> parseFromDocument(Document doc) {
        if (doc == null) {
            return Optional.empty();
        }

        try {
            var scripts = doc.select("script[type='application/ld+json']");

            for (var script : scripts) {
                try {
                    var json = MAPPER.readTree(script.data());
                    if (isJobPosting(json)) {
                        return Optional.of(extractJobPosting(json));
                    }

                    if (json.isArray()) {
                        for (var node : json) {
                            if (isJobPosting(node)) {
                                return Optional.of(extractJobPosting(node));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse JSON-LD script: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse HTML for JSON-LD: {}", e.getMessage());
        }

        return Optional.empty();
    }

    private static boolean isJobPosting(JsonNode node) {
        var type = node.path("@type").asText("");
        return "JobPosting".equals(type);
    }

    private static JsonLdJobPosting extractJobPosting(JsonNode node) {
        var company = node.path("hiringOrganization").path("name").asText(null);
        var location = node.path("jobLocation").path("address").path("addressLocality").asText(null);
        var workModelRaw = node.path("jobLocationType").asText(null);
        var workModel = "TELECOMMUTE".equalsIgnoreCase(workModelRaw) ? "Remoto" : workModelRaw;

        return new JsonLdJobPosting(
                node.path("title").asText(null),
                node.path("description").asText(null),
                company,
                node.path("datePosted").asText(null),
                location,
                node.path("employmentType").asText(null),
                workModel
        );
    }
}
