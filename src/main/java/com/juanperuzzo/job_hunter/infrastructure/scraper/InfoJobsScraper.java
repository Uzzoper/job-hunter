package com.juanperuzzo.job_hunter.infrastructure.scraper;

import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.domain.model.Job;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class InfoJobsScraper implements ScraperPort {

    private static final Logger log = LoggerFactory.getLogger(InfoJobsScraper.class);
    private static final List<String> REMOTE_TERMS = List.of("home office", "remoto", "todo brasil", "teletrabalho");
    private static final List<String> DEFAULT_EXCLUDE_TERMS = List.of(
            "bdr",
            "desenvolvedor de negocios",
            "desenvolvimento de negocios",
            "business development");

    private final String baseUrl;
    private final boolean enabled;
    private final RestClient restClient;
    private final List<String> keywords;
    private final List<Pattern> excludePatterns;
    private final List<String> locations;
    private final int maxPages;
    private final int maxAgeDays;
    private final int timeoutSeconds;
    private final long delayMillis;

    public InfoJobsScraper(String baseUrl, boolean enabled, List<String> keywords, List<String> excludeKeywords,
            List<String> locations, int maxPages, int maxAgeDays, int timeoutSeconds, long delayMillis) {
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.enabled = enabled;
        this.keywords = normalizeList(keywords);
        this.excludePatterns = buildExcludePatterns(excludeKeywords);
        this.locations = normalizeList(locations).stream()
                .map(InfoJobsScraper::normalize)
                .toList();
        this.maxPages = Math.max(1, maxPages);
        this.maxAgeDays = Math.max(1, maxAgeDays);
        this.timeoutSeconds = timeoutSeconds;
        this.delayMillis = Math.max(0, delayMillis);

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "JobHunter/1.0")
                .build();
    }

    @Override
    public List<Job> fetch() {
        if (!enabled) {
            log.debug("InfoJobs scraper is disabled");
            return List.of();
        }

        var uniqueJobs = new LinkedHashMap<String, Job>();
        var requestCount = 0;
        var totalParsed = 0;
        var countKeywords = 0;
        var countExcluded = 0;
        var countLocation = 0;
        var countAge = 0;
        var countDuplicate = 0;

        for (var keyword : keywords) {
            for (var page = 1; page <= maxPages; page++) {
                pauseBetweenRequests(requestCount);
                var html = fetchSearchPage(keyword, page);

                if (isBotChallenge(html)) {
                    throw new ScraperException("InfoJobs returned a bot challenge page");
                }

                var parsedJobs = parseJobs(html);
                totalParsed += parsedJobs.size();

                for (var parsedJob : parsedJobs) {
                    if (!matchesKeywords(parsedJob)) {
                        countKeywords++;
                        continue;
                    }
                    if (isExcluded(parsedJob.job().title())) {
                        countExcluded++;
                        continue;
                    }
                    if (!matchesLocation(parsedJob)) {
                        countLocation++;
                        continue;
                    }
                    if (parsedJob.job().postedAt().isBefore(LocalDate.now().minusDays(maxAgeDays))) {
                        countAge++;
                        continue;
                    }
                    if (uniqueJobs.putIfAbsent(parsedJob.job().url(), parsedJob.job()) != null) {
                        countDuplicate++;
                    }
                }

                requestCount++;
            }
        }

        log.info(
                "InfoJobs parsed: {}. Dropped by keywords: {}, excluded by regex: {}, dropped by location: {}, dropped by age: {}, duplicates: {}. Accepted: {}",
                totalParsed, countKeywords, countExcluded, countLocation, countAge, countDuplicate, uniqueJobs.size());
        return List.copyOf(uniqueJobs.values());
    }

    private String fetchSearchPage(String keyword, int page) {
        var uri = buildSearchUri(keyword, page);

        try {
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ScraperException("InfoJobs endpoint returned status " + res.getStatusCode());
                    })
                    .body(String.class);
        } catch (ScraperException e) {
            throw e;
        } catch (Exception e) {
            if (isTimeout(e)) {
                throw new ScraperException("InfoJobs scraper timed out after " + timeoutSeconds + "s", e);
            }
            throw new ScraperException("Failed to fetch InfoJobs page: " + e.getMessage(), e);
        }
    }

    private URI buildSearchUri(String keyword, int page) {
        var encodedKeyword = URLEncoder.encode(keyword.trim().toLowerCase(Locale.ROOT), StandardCharsets.UTF_8)
                .replace("+", "%2B");
        var pageQuery = page > 1 ? "?Page=" + page : "";
        return URI.create(baseUrl + "/vagas-de-emprego-" + encodedKeyword + ".aspx" + pageQuery);
    }

    private List<ParsedJob> parseJobs(String html) {
        var document = Jsoup.parse(html, baseUrl);
        var cards = document.select("[data-testid=job-card], article.js_rowCard, article.job-card, div.job-card");
        if (cards.isEmpty()) {
            return parseJobsFromLinks(document);
        }

        var jobs = new ArrayList<ParsedJob>();

        for (var card : cards) {
            mapCardToJob(card).ifPresent(jobs::add);
        }

        if (jobs.isEmpty()) {
            return parseJobsFromLinks(document);
        }

        return jobs;
    }

    private List<ParsedJob> parseJobsFromLinks(Document document) {
        var jobs = new ArrayList<ParsedJob>();
        var titleLinks = document.select("a[href*=/vaga-de-][href*=__]");

        for (var titleLink : titleLinks) {
            mapLinkToJob(titleLink).ifPresent(jobs::add);
        }

        log.debug("InfoJobs fallback parser found {} candidate links and mapped {} jobs", titleLinks.size(), jobs.size());
        return jobs;
    }

    private Optional<ParsedJob> mapLinkToJob(Element titleLink) {
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
        var postedAt = parsePostedAt(extractPostedDate(cardText));
        if (postedAt.isEmpty()) {
            log.debug("Skipping InfoJobs fallback card without parseable date: {}", title);
            return Optional.empty();
        }

        var company = extractCompanyFromLinks(titleLink, card.get());
        var location = extractLocation(cardText);
        var workModel = extractWorkModel(cardText);
        var description = extractDescription(cardText, workModel);
        var job = new Job(null, title, company, url, description, postedAt.get(), Optional.empty());

        return Optional.of(new ParsedJob(job, location, workModel));
    }

    private Optional<Element> findLikelyCardContainer(Element titleLink) {
        var current = titleLink.parent();

        for (var depth = 0; current != null && depth < 7; depth++) {
            var text = clean(current.text());
            if (text.length() >= 40 && text.length() <= 2_000 && parsePostedAt(extractPostedDate(text)).isPresent()) {
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
                .map(InfoJobsScraper::clean)
                .filter(text -> !text.isEmpty())
                .filter(text -> !normalize(text).contains("vagas semelhantes"))
                .findFirst()
                .orElse("");
    }

    private String extractPostedDate(String text) {
        var normalizedText = normalize(text);
        var relativeMatcher = Pattern.compile(".*\\b(hoje|ontem|nova|ha\\s+\\d+\\s+dias?|a\\s+\\d+\\s+dias?)\\b.*")
                .matcher(normalizedText);
        if (relativeMatcher.matches()) {
            return relativeMatcher.group(1);
        }

        var dayMonthMatcher = Pattern.compile(".*?\\b(\\d{1,2}\\s+(?:jan|fev|mar|abr|mai|jun|jul|ago|set|out|nov|dez))\\b.*")
                .matcher(normalizedText);
        if (dayMonthMatcher.matches()) {
            return dayMonthMatcher.group(1);
        }

        return "";
    }

    private String extractLocation(String text) {
        var todoBrasilMatcher = Pattern.compile("(?i).*\\b(todo brasil)\\b.*").matcher(text);
        if (todoBrasilMatcher.matches()) {
            return "Todo Brasil";
        }

        var cityStateMatcher = Pattern.compile(".*?([\\p{L} .'-]+ - [A-Z]{2})(?:,|\\s|$).*").matcher(text);
        if (cityStateMatcher.matches()) {
            return clean(cityStateMatcher.group(1));
        }

        return "";
    }

    private String extractWorkModel(String text) {
        var normalizedText = normalize(text);
        if (normalizedText.contains("home office")) {
            return "Home office";
        }
        if (normalizedText.contains("hibrido")) {
            return "Híbrido";
        }
        if (normalizedText.contains("presencial")) {
            return "Presencial";
        }
        return "";
    }

    private String extractDescription(String text, String workModel) {
        if (workModel.isEmpty()) {
            return text;
        }

        var index = normalize(text).indexOf(normalize(workModel));
        if (index < 0) {
            return text;
        }

        return clean(text.substring(Math.min(text.length(), index + workModel.length())));
    }

    private Optional<ParsedJob> mapCardToJob(Element card) {
        var titleElement = first(card, "[data-testid=job-title], h2 a, h2, a[title], .job-title, .vaga-title");
        if (titleElement.isEmpty()) {
            log.debug("Skipping InfoJobs card without title");
            return Optional.empty();
        }

        var title = clean(titleElement.get().text());
        var url = extractUrl(titleElement.get(), card);
        var postedAt = parsePostedAt(textFrom(card, "[data-testid=posted-date], .posted-date, .date, .data"));

        if (title.isEmpty() || url.isEmpty() || postedAt.isEmpty()) {
            log.debug("Skipping incomplete InfoJobs card: title='{}', url='{}'", title, url);
            return Optional.empty();
        }

        var company = textFrom(card, "[data-testid=company-name], .company, .empresa, .company-name");
        var location = textFrom(card, "[data-testid=job-location], .location, .localizacao, .job-location");
        var workModel = textFrom(card, "[data-testid=work-model], .work-model, .modelo-trabalho");
        var description = textFrom(card, "[data-testid=job-snippet], .description, .descricao, .snippet");
        var job = new Job(null, title, company, url, description, postedAt.get(), Optional.empty());

        return Optional.of(new ParsedJob(job, location, workModel));
    }

    private Optional<Element> first(Element root, String selector) {
        return root.select(selector).stream().findFirst();
    }

    private String extractUrl(Element titleElement, Element card) {
        var href = titleElement.hasAttr("href")
                ? titleElement.absUrl("href")
                : "";

        if (!href.isEmpty()) {
            return href;
        }

        return card.select("a[href]").stream()
                .map(element -> element.absUrl("href"))
                .filter(url -> !url.isEmpty())
                .findFirst()
                .orElse("");
    }

    private Optional<LocalDate> parsePostedAt(String rawDate) {
        var date = normalize(rawDate);
        if (date.isEmpty() || date.contains("nova")) {
            return Optional.of(LocalDate.now());
        }
        if (date.contains("hoje")) {
            return Optional.of(LocalDate.now());
        }
        if (date.contains("ontem")) {
            return Optional.of(LocalDate.now().minusDays(1));
        }

        var daysAgoMatcher = Pattern.compile(".*(?:ha|a)\\s+(\\d+)\\s+dias?.*").matcher(date);
        if (daysAgoMatcher.matches()) {
            return Optional.of(LocalDate.now().minusDays(Long.parseLong(daysAgoMatcher.group(1))));
        }

        var dayMonthMatcher = Pattern.compile(".*?(\\d{1,2})\\s+([a-z]{3,}).*").matcher(date);
        if (dayMonthMatcher.matches()) {
            var day = Integer.parseInt(dayMonthMatcher.group(1));
            var month = parseMonth(dayMonthMatcher.group(2));
            if (month.isEmpty()) {
                return Optional.empty();
            }

            var parsedDate = LocalDate.of(LocalDate.now().getYear(), month.get(), day);
            if (parsedDate.isAfter(LocalDate.now())) {
                parsedDate = parsedDate.minusYears(1);
            }
            return Optional.of(parsedDate);
        }

        return Optional.empty();
    }

    private Optional<Month> parseMonth(String rawMonth) {
        return switch (rawMonth.substring(0, Math.min(3, rawMonth.length()))) {
            case "jan" -> Optional.of(Month.JANUARY);
            case "fev" -> Optional.of(Month.FEBRUARY);
            case "mar" -> Optional.of(Month.MARCH);
            case "abr" -> Optional.of(Month.APRIL);
            case "mai" -> Optional.of(Month.MAY);
            case "jun" -> Optional.of(Month.JUNE);
            case "jul" -> Optional.of(Month.JULY);
            case "ago" -> Optional.of(Month.AUGUST);
            case "set" -> Optional.of(Month.SEPTEMBER);
            case "out" -> Optional.of(Month.OCTOBER);
            case "nov" -> Optional.of(Month.NOVEMBER);
            case "dez" -> Optional.of(Month.DECEMBER);
            default -> Optional.empty();
        };
    }

    private boolean matchesKeywords(ParsedJob parsedJob) {
        if (keywords.isEmpty()) {
            return true;
        }

        var title = normalize(parsedJob.job().title());
        return keywords.stream()
                .map(InfoJobsScraper::normalize)
                .map(keyword -> keyword.split("[^a-z0-9]+"))
                .anyMatch(tokens -> Arrays.stream(tokens)
                        .filter(token -> token.length() > 2)
                        .allMatch(title::contains));
    }

    private boolean isExcluded(String title) {
        var normalizedTitle = normalize(title);
        return excludePatterns.stream()
                .anyMatch(pattern -> pattern.matcher(normalizedTitle).find());
    }

    private boolean matchesLocation(ParsedJob parsedJob) {
        if (locations.isEmpty()) {
            return true;
        }

        var searchableText = normalize(String.join(" ", parsedJob.location(), parsedJob.workModel()));
        if (REMOTE_TERMS.stream().map(InfoJobsScraper::normalize).anyMatch(searchableText::contains)) {
            return true;
        }

        return locations.stream().anyMatch(location -> containsLocation(searchableText, location));
    }

    private boolean containsLocation(String searchableText, String location) {
        if (location.length() <= 2) {
            var pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(location) + "(?![\\p{L}\\p{N}_])");
            return pattern.matcher(searchableText).find();
        }

        return searchableText.contains(location);
    }

    private boolean isBotChallenge(String html) {
        var normalizedHtml = normalize(html);
        return normalizedHtml.contains("captcha")
                || normalizedHtml.contains("nao e um robo")
                || normalizedHtml.contains("not a robot")
                || normalizedHtml.contains("access denied");
    }

    private void pauseBetweenRequests(int requestCount) {
        if (requestCount == 0 || delayMillis == 0) {
            return;
        }

        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScraperException("InfoJobs scraper was interrupted", e);
        }
    }

    private boolean isTimeout(Exception e) {
        var message = e.getMessage();
        return message != null
                && (message.toLowerCase(Locale.ROOT).contains("timeout")
                        || message.toLowerCase(Locale.ROOT).contains("timed out"));
    }

    private String textFrom(Element root, String selector) {
        return first(root, selector)
                .map(Element::text)
                .map(InfoJobsScraper::clean)
                .orElse("");
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static List<Pattern> buildExcludePatterns(List<String> configuredTerms) {
        var terms = new ArrayList<String>();
        terms.addAll(DEFAULT_EXCLUDE_TERMS);
        terms.addAll(normalizeList(configuredTerms));

        return terms.stream()
                .map(InfoJobsScraper::normalize)
                .distinct()
                .map(term -> Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}_])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS))
                .toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        var cleaned = clean(value).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(cleaned, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static String removeTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record ParsedJob(Job job, String location, String workModel) {
    }
}
