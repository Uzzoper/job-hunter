package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.HtmlStrategy;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class InfoJobsProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(InfoJobsProvider.class);

    private final String providerId;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final List<String> keywords;
    private final int maxPages;
    private final ExponentialBackoffRetry retry;

    public InfoJobsProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            ExponentialBackoffRetry retry) {
        this.providerId = "infojobs";
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.timeoutSeconds = timeoutSeconds;
        this.keywords = keywords;
        this.maxPages = maxPages;
        this.retry = retry;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();

        for (var keyword : keywords) {
            for (var page = 1; page <= maxPages; page++) {
                var uri = buildSearchUri(keyword, page);
                try {
                    var jobs = retry.execute(() -> {
                        var html = fetchHtml(uri);
                        if (isBotChallenge(html)) {
                            throw new ScraperException("InfoJobs returned a bot challenge page");
                        }
                        return parseJobs(html);
                    });

                    for (var job : jobs) {
                        uniqueJobs.putIfAbsent(job.url(), job);
                    }

                    log.debug("{}: fetched {} jobs for keyword '{}' page {}", providerId, jobs.size(), keyword, page);
                } catch (Exception e) {
                    log.error("{}: failed for keyword '{}' page {}: {}", providerId, keyword, page, e.getMessage());
                }
            }
        }

        var result = List.copyOf(uniqueJobs.values());
        log.info("{}: total unique jobs fetched: {}", providerId, result.size());
        return result;
    }

    private String fetchHtml(URI uri) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        var client = org.springframework.web.client.RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "JobHunter/1.0")
                .build();
        return client.get()
                .uri(uri)
                .retrieve()
                .body(String.class);
    }

    private URI buildSearchUri(String keyword, int page) {
        var encodedKeyword = java.net.URLEncoder.encode(
                keyword.trim().toLowerCase(java.util.Locale.ROOT), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%2B");
        var pageQuery = page > 1 ? "?Page=" + page : "";
        return URI.create(baseUrl + "/vagas-de-emprego-" + encodedKeyword + ".aspx" + pageQuery);
    }

    private List<RawJob> parseJobs(String html) {
        var document = org.jsoup.Jsoup.parse(html, baseUrl);
        var cards = document.select("[data-testid=job-card], article.js_rowCard, article.job-card, div.job-card");
        if (cards.isEmpty()) {
            return parseJobsFromLinks(document);
        }

        var jobs = new ArrayList<RawJob>();
        for (var card : cards) {
            mapCardToRawJob(card).ifPresent(jobs::add);
        }

        if (jobs.isEmpty()) {
            return parseJobsFromLinks(document);
        }

        return jobs;
    }

    private List<RawJob> parseJobsFromLinks(Document document) {
        var jobs = new ArrayList<RawJob>();
        var titleLinks = document.select("a[href*=/vaga-de-][href*=__]");

        for (var titleLink : titleLinks) {
            mapLinkToRawJob(titleLink).ifPresent(jobs::add);
        }

        return jobs;
    }

    private Optional<RawJob> mapLinkToRawJob(Element titleLink) {
        var title = clean(titleLink.text());
        var url = titleLink.absUrl("href");
        if (title.isEmpty() || url.isEmpty()) {
            return Optional.empty();
        }

        var card = findLikelyCardContainer(titleLink);
        if (card.isEmpty()) {
            return Optional.empty();
        }

        var cardText = clean(card.get().text());
        var company = extractCompanyFromLinks(titleLink, card.get());
        var location = extractLocation(cardText);
        var workModel = extractWorkModel(cardText);
        var description = extractDescription(cardText, workModel);

        return Optional.of(new RawJob(
                title, company, url, description, null, location, workModel, "infojobs", null));
    }

    private Optional<RawJob> mapCardToRawJob(Element card) {
        var titleElement = first(card, "[data-testid=job-title], h2 a, h2, a[title], .job-title, .vaga-title");
        if (titleElement.isEmpty()) {
            return Optional.empty();
        }

        var title = clean(titleElement.get().text());
        var url = extractUrl(titleElement.get(), card);
        if (title.isEmpty() || url.isEmpty()) {
            return Optional.empty();
        }

        var company = textFrom(card, "[data-testid=company-name], .company, .empresa, .company-name");
        var location = textFrom(card, "[data-testid=job-location], .location, .localizacao, .job-location");
        var workModel = textFrom(card, "[data-testid=work-model], .work-model, .modelo-trabalho");
        var description = textFrom(card, "[data-testid=job-snippet], .description, .descricao, .snippet");
        var rawDate = textFrom(card, "[data-testid=posted-date], .posted-date, .date, .data");

        return Optional.of(new RawJob(title, company, url, description, rawDate, location, workModel, "infojobs", null));
    }

    private Optional<Element> findLikelyCardContainer(Element titleLink) {
        var current = titleLink.parent();
        for (var depth = 0; current != null && depth < 7; depth++) {
            var text = clean(current.text());
            if (text.length() >= 40 && text.length() <= 2_000) {
                return Optional.of(current);
            }
            current = current.parent();
        }
        return Optional.empty();
    }

    private String extractCompanyFromLinks(Element titleLink, Element card) {
        return card.select("a[href]").stream()
                .filter(link -> !link.equals(titleLink))
                .map(Element::text)
                .map(InfoJobsProvider::clean)
                .filter(text -> !text.isEmpty())
                .filter(text -> !text.toLowerCase(java.util.Locale.ROOT).contains("vagas semelhantes"))
                .findFirst()
                .orElse("");
    }

    private String extractLocation(String text) {
        var todoBrasilMatcher = java.util.regex.Pattern.compile("(?i).*\\b(todo brasil)\\b.*").matcher(text);
        if (todoBrasilMatcher.matches()) {
            return "Todo Brasil";
        }
        var cityStateMatcher = java.util.regex.Pattern.compile(".*?([\\p{L} .'-]+ - [A-Z]{2})(?:,|\\s|$).*").matcher(text);
        if (cityStateMatcher.matches()) {
            return clean(cityStateMatcher.group(1));
        }
        return "";
    }

    private String extractWorkModel(String text) {
        var normalized = normalize(text);
        if (normalized.contains("home office")) return "Home office";
        if (normalized.contains("hibrido")) return "Híbrido";
        if (normalized.contains("presencial")) return "Presencial";
        return "";
    }

    private String extractDescription(String text, String workModel) {
        if (workModel.isEmpty()) return text;
        var index = normalize(text).indexOf(normalize(workModel));
        if (index < 0) return text;
        return clean(text.substring(Math.min(text.length(), index + workModel.length())));
    }

    private boolean isBotChallenge(String html) {
        var normalized = normalize(html);
        return normalized.contains("captcha")
                || normalized.contains("nao e um robo")
                || normalized.contains("not a robot")
                || normalized.contains("access denied");
    }

    private static String extractUrl(Element titleElement, Element card) {
        var href = titleElement.hasAttr("href") ? titleElement.absUrl("href") : "";
        if (!href.isEmpty()) return href;
        return card.select("a[href]").stream()
                .map(element -> element.absUrl("href"))
                .filter(url -> !url.isEmpty())
                .findFirst()
                .orElse("");
    }

    private static Optional<Element> first(Element root, String selector) {
        return root.select(selector).stream().findFirst();
    }

    private static String textFrom(Element root, String selector) {
        return first(root, selector)
                .map(Element::text)
                .map(InfoJobsProvider::clean)
                .orElse("");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        var cleaned = clean(value).toLowerCase(java.util.Locale.ROOT);
        var normalized = java.text.Normalizer.normalize(cleaned, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.replaceAll("\\bjr\\b", "junior");
    }

    private static String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
