package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.UrlNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.parser.JsonLdParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class InfoJobsProvider implements ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(InfoJobsProvider.class);

    private static final int DEFAULT_DETAIL_CONCURRENCY = 2;
    private static final int DEFAULT_DETAIL_TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_MAX_DETAIL_FETCH = 20;
    private static final String DETAIL_RATE_LIMIT_KEY = "infojobs-detail";

    private final String providerId;
    private final String baseUrl;
    private final int timeoutSeconds;
    private final List<String> keywords;
    private final int maxPages;
    private final ExponentialBackoffRetry retry;
    private final int detailConcurrency;
    private final RateLimiter rateLimiter;
    private final int maxDetailFetch;
    private final org.springframework.web.client.RestClient sharedRestClient;
    private final org.springframework.web.client.RestClient detailRestClient;

    public InfoJobsProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            ExponentialBackoffRetry retry,
            org.springframework.web.client.RestClient sharedRestClient,
            RateLimiter rateLimiter) {
        this(baseUrl, timeoutSeconds, keywords, maxPages, retry,
                DEFAULT_DETAIL_CONCURRENCY, DEFAULT_DETAIL_TIMEOUT_SECONDS,
                sharedRestClient, buildDetailRestClient(DEFAULT_DETAIL_TIMEOUT_SECONDS),
                rateLimiter,
                DEFAULT_MAX_DETAIL_FETCH);
    }

    public InfoJobsProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            ExponentialBackoffRetry retry,
            int detailConcurrency,
            int detailTimeoutSeconds,
            org.springframework.web.client.RestClient sharedRestClient,
            RateLimiter rateLimiter) {
        this(baseUrl, timeoutSeconds, keywords, maxPages, retry,
                detailConcurrency, detailTimeoutSeconds,
                sharedRestClient, buildDetailRestClient(detailTimeoutSeconds),
                rateLimiter,
                DEFAULT_MAX_DETAIL_FETCH);
    }

    public InfoJobsProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            ExponentialBackoffRetry retry,
            int detailConcurrency,
            int detailTimeoutSeconds,
            org.springframework.web.client.RestClient sharedRestClient,
            RateLimiter rateLimiter,
            int maxDetailFetch) {
        this(baseUrl, timeoutSeconds, keywords, maxPages, retry,
                detailConcurrency, detailTimeoutSeconds,
                sharedRestClient, buildDetailRestClient(detailTimeoutSeconds),
                rateLimiter, maxDetailFetch);
    }

    public InfoJobsProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            ExponentialBackoffRetry retry,
            int detailConcurrency,
            int detailTimeoutSeconds,
            org.springframework.web.client.RestClient sharedRestClient,
            org.springframework.web.client.RestClient detailRestClient,
            RateLimiter rateLimiter,
            int maxDetailFetch) {
        this.providerId = "infojobs";
        this.baseUrl = UrlNormalizer.noTrailingSlash(baseUrl);
        this.timeoutSeconds = timeoutSeconds;
        this.keywords = keywords;
        this.maxPages = maxPages;
        this.retry = retry;
        this.detailConcurrency = detailConcurrency;
        this.rateLimiter = rateLimiter;
        this.maxDetailFetch = maxDetailFetch;
        this.sharedRestClient = sharedRestClient;
        this.detailRestClient = detailRestClient;
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

        var jobs = List.copyOf(uniqueJobs.values());
        var enriched = enrichWithDetails(jobs);
        log.info("{}: total unique jobs fetched: {}", providerId, enriched.size());
        return enriched;
    }

    /**
     * Fetch each job's detail page concurrently (virtual threads bounded by a
     * {@code Semaphore}), rate-limited per domain, with retry. On any failure the
     * card snippet is kept and {@code metadata.detailFailed=true} is set — per-card
     * failure is never allowed to discard the job or abort the batch.
     *
     * <p>At most {@code maxDetailFetch} jobs are enriched; the rest keep their card
     * snippet with {@code metadata.detailSkipped=true} (not {@code detailFailed})
     * to bound aggregate fetch volume — an untried job is not a failure.
     */
    private List<RawJob> enrichWithDetails(List<RawJob> jobs) {
        if (jobs.isEmpty() || detailConcurrency <= 0) {
            return jobs;
        }

        var toEnrich = jobs.subList(0, Math.min(jobs.size(), maxDetailFetch));
        var remaining = jobs.subList(Math.min(jobs.size(), maxDetailFetch), jobs.size());

        var enriched = enrichBatch(toEnrich);

        var results = new ArrayList<RawJob>(jobs.size());
        results.addAll(enriched);
        for (var job : remaining) {
            results.add(markDetailSkipped(job));
        }
        return results;
    }

    private List<RawJob> enrichBatch(List<RawJob> jobs) {
        if (jobs.isEmpty()) {
            return List.of();
        }

        var semaphore = new Semaphore(detailConcurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = jobs.stream()
                    .map(job -> executor.submit(() -> enrichOne(job, semaphore)))
                    .toList();

            var results = new ArrayList<RawJob>(jobs.size());
            for (var future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("{}: detail enrichment task failed unexpectedly", providerId);
                }
            }
            return results;
        }
    }

    private RawJob enrichOne(RawJob job, Semaphore semaphore) {
        try {
            semaphore.acquire();
            try {
                var detailUri = URI.create(job.url());
                var detailHtml = fetchDetailHtml(detailUri);
                return mergeDetail(job, detailHtml);
            } catch (Exception e) {
                log.debug("{}: detail fetch failed for {}: {}, keeping snippet", providerId, job.url(), e.getMessage());
                return markDetailFailed(job);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return markDetailFailed(job);
        }
    }

    private String fetchDetailHtml(URI uri) {
        rateLimiter.acquire(DETAIL_RATE_LIMIT_KEY);
        return retry.execute(() -> {
            var html = fetchHtmlWithClient(uri, detailRestClient);
            if (isBotChallenge(html)) {
                throw new ScraperException("InfoJobs detail returned a bot challenge page");
            }
            return html;
        });
    }

    /**
     * Merge full detail text (and optional companyWebsite) into a job. When the
     * detail body has no usable description, the card snippet is preserved.
     */
    private RawJob mergeDetail(RawJob job, String detailHtml) {
        if (detailHtml == null || detailHtml.isBlank()) {
            return markDetailFailed(job);
        }

        var doc = org.jsoup.Jsoup.parse(detailHtml, baseUrl);
        var description = extractDetailDescription(doc, job);
        var website = extractCompanyWebsite(doc, job.company());

        var metadata = new HashMap<>(job.metadata());
        if (website != null && !website.isBlank()) {
            metadata.put("companyWebsite", website);
        }

        return new RawJob(
                job.title(),
                job.company(),
                job.url(),
                description,
                job.rawDate(),
                job.location(),
                job.workModel(),
                job.source(),
                metadata);
    }

    /**
     * Try HTML selectors for the full description, then JSON-LD, then fall back to
     * the existing card snippet.
     */
    private String extractDetailDescription(Document doc, RawJob job) {
        for (var selector : List.of("[data-testid=job-description]", ".description", ".job-description")) {
            var element = first(doc, selector);
            if (element.isPresent()) {
                var text = clean(element.get().text());
                if (text.length() > job.description().length()) {
                    return text;
                }
            }
        }

        var jsonLd = new JsonLdParser().parseFromDocument(doc);
        if (jsonLd.isPresent()) {
            var desc = jsonLd.get().description();
            if (desc != null && !desc.isBlank()) {
                return clean(desc);
            }
        }

        // No fuller text available — keep the card snippet
        return job.description();
    }

    /**
     * Extract an absolute company website URL from a card/detail document, following
     * the spec (Scenario 11): an anchor near the company name or an anchor whose
     * href contains "company". Matches only anchors that look like a company link
     * (never a job/navigation link). Returns null when absent.
     */
    private String extractCompanyWebsite(Element root, String companyName) {
        // 1. Anchor inside/next to a company-name element
        for (var selector : List.of(
                "[data-testid=company-name] a[href]",
                ".company a[href], .empresa a[href], .company-name a[href]")) {
            var anchor = root.selectFirst(selector);
            if (anchor != null) {
                var url = absoluteUrl(anchor, baseUrl);
                if (url != null) {
                    return url;
                }
            }
        }
        // 2. Any anchor whose href contains "company"
        var companyKeywordAnchor = root.selectFirst("a[href*=company]");
        if (companyKeywordAnchor != null) {
            var url = absoluteUrl(companyKeywordAnchor, baseUrl);
            if (url != null) {
                return url;
            }
        }
        // 3. Anchor whose text matches the known company name (the "near company name" rule)
        if (companyName != null && !companyName.isBlank()) {
            var expected = clean(companyName);
            for (var anchor : root.select("a[href]")) {
                if (!clean(anchor.text()).equalsIgnoreCase(expected)) {
                    continue;
                }
                var href = anchor.absUrl("href");
                if (href.isBlank() || href.contains("/vagas") || href.contains("/vaga-de")) {
                    continue;
                }
                return UrlNormalizer.noTrailingSlash(href);
            }
        }
        return null;
    }

    private String fetchHtml(URI uri) {
        return fetchHtmlWithClient(uri, sharedRestClient);
    }

    /** Fetch raw HTML with a specific REST client (search pages use the shared client; details the dedicated one). */
    private String fetchHtmlWithClient(URI uri, org.springframework.web.client.RestClient client) {
        var bytes = client.get()
                .uri(uri)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Standalone RestClient honoring {@code detail-timeout-seconds} (used when no explicit detail bean is injected). */
    private static org.springframework.web.client.RestClient buildDetailRestClient(int timeoutSeconds) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
        return org.springframework.web.client.RestClient.builder()
                .requestFactory(requestFactory)
                .build();
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
        var website = extractCompanyWebsite(card.get(), company);

        return Optional.of(new RawJob(
                title, company, url, description, null, location, workModel, "infojobs",
                metadata(website)));
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
        var website = extractCompanyWebsite(card, company);

        return Optional.of(new RawJob(
                title, company, url, description, rawDate, location, workModel, "infojobs",
                metadata(website)));
    }

    private static HashMap<String, String> metadata(String website) {
        var metadata = new HashMap<String, String>();
        if (website != null && !website.isBlank()) {
            metadata.put("companyWebsite", website);
        }
        return metadata;
    }

    private static RawJob markDetailFailed(RawJob job) {
        var metadata = new HashMap<>(job.metadata());
        metadata.put("detailFailed", "true");
        return new RawJob(
                job.title(), job.company(), job.url(), job.description(),
                job.rawDate(), job.location(), job.workModel(), job.source(), metadata);
    }

    /** Mark a job whose detail page was never attempted (beyond the cap) as skipped, not failed. */
    private static RawJob markDetailSkipped(RawJob job) {
        var metadata = new HashMap<>(job.metadata());
        metadata.put("detailSkipped", "true");
        return new RawJob(
                job.title(), job.company(), job.url(), job.description(),
                job.rawDate(), job.location(), job.workModel(), job.source(), metadata);
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

    /** Resolve a possibly-relative href to an absolute URL against the base. */
    private static String absoluteUrl(Element element, String baseUrl) {
        var abs = element.absUrl("href");
        if (abs.isBlank()) return null;
        return UrlNormalizer.noTrailingSlash(abs);
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
}
