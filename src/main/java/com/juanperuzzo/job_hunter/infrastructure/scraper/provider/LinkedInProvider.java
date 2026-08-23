package com.juanperuzzo.job_hunter.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.juanperuzzo.job_hunter.infrastructure.config.LinkedInScraperProperties;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.RetryStrategy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class LinkedInProvider implements com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy {

    private static final Logger log = LoggerFactory.getLogger(LinkedInProvider.class);

    private static final String PROVIDER_ID = "linkedin";
    private static final String LIST_PATH = "/jobs/search";
    private static final String DETAIL_PATH_PREFIX = "/jobs/view/";
    private static final String USER_AGENT = "JobHunter/1.0";
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("/jobs/view/([^/?#]+)");

    private final String baseUrl;
    private final int timeoutSeconds;
    private final List<String> keywords;
    private final int maxPages;
    private final RetryStrategy retry;
    private final long detailFetchDelayMillis;
    private final String location;
    private final List<String> geoIds;
    private final List<String> seniority;
    private final List<String> workType;
    private final String timeRange;
    private final int maxJobs;

    public LinkedInProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            RetryStrategy retry) {
        this(baseUrl, timeoutSeconds, keywords, maxPages, retry, 1000L, "", List.of(), List.of(), List.of(), "", 25);
    }

    public LinkedInProvider(
            String baseUrl,
            int timeoutSeconds,
            List<String> keywords,
            int maxPages,
            RetryStrategy retry,
            long detailFetchDelayMillis,
            String location,
            List<String> geoIds,
            List<String> seniority,
            List<String> workType,
            String timeRange,
            int maxJobs) {
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.timeoutSeconds = timeoutSeconds;
        this.keywords = keywords;
        this.maxPages = maxPages;
        this.retry = retry;
        this.detailFetchDelayMillis = detailFetchDelayMillis;
        this.location = location;
        this.geoIds = geoIds;
        this.seniority = seniority;
        this.workType = workType;
        this.timeRange = timeRange;
        this.maxJobs = maxJobs;
    }

    public LinkedInProvider(
            LinkedInScraperProperties properties,
            ExponentialBackoffRetry retry) {
        this.baseUrl = removeTrailingSlash(properties.baseUrl());
        this.timeoutSeconds = properties.timeoutSeconds();
        this.keywords = properties.keywords();
        this.maxPages = properties.maxPages();
        this.retry = retry;
        this.detailFetchDelayMillis = properties.detailFetchDelayMillis();
        this.location = properties.locations();
        this.geoIds = properties.geoIds();
        this.seniority = properties.seniority();
        this.workType = properties.workType();
        this.timeRange = properties.timeRange();
        this.maxJobs = properties.maxJobs();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<RawJob> extract() {
        var uniqueJobs = new HashMap<String, RawJob>();
        int totalFetched = 0;

        for (var keyword : keywords) {
            for (var page = 0; page < maxPages && totalFetched < maxJobs; page++) {
                var start = page * 25;
                var uri = buildSearchUri(keyword, start);
                try {
                    var jobs = retry.execute(() -> fetchAndParseListPage(uri));

                    for (var job : jobs) {
                        if (totalFetched >= maxJobs) {
                            break;
                        }
                        uniqueJobs.putIfAbsent(job.url(), job);
                        totalFetched++;
                    }

                    log.debug("{}: fetched {} jobs for keyword '{}' page {} (start={})",
                            PROVIDER_ID, jobs.size(), keyword, page + 1, start);
                } catch (ScraperException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("{}: failed for keyword '{}' page {}: {}", PROVIDER_ID, keyword, page + 1, e.getMessage());
                }
            }
        }

        var jobList = new ArrayList<>(uniqueJobs.values());
        if (jobList.size() > maxJobs) {
            jobList = new ArrayList<>(jobList.subList(0, maxJobs));
        }

        var enrichedJobs = enrichWithDetailPages(jobList);

        log.info("{}: total unique jobs fetched: {}", PROVIDER_ID, enrichedJobs.size());
        return enrichedJobs;
    }

    private List<RawJob> fetchAndParseListPage(URI uri) {
        var html = fetchHtml(uri);
        if (isBotChallenge(html)) {
            throw new ScraperException("LinkedIn returned a bot challenge page");
        }
        return parseJobs(html);
    }

    private URI buildSearchUri(String keyword, int start) {
        var encodedKeyword = URLEncoder.encode(keyword.trim().toLowerCase(java.util.Locale.ROOT), StandardCharsets.UTF_8)
                .replace("+", "%20");

        var builder = new StringBuilder(baseUrl)
                .append(LIST_PATH)
                .append("?keywords=").append(encodedKeyword)
                .append("&start=").append(start);

        if (!location.isBlank()) {
            builder.append("&location=").append(URLEncoder.encode(location, StandardCharsets.UTF_8));
        }
        if (!geoIds.isEmpty()) {
            for (var geoId : geoIds) {
                builder.append("&geoId=").append(URLEncoder.encode(geoId, StandardCharsets.UTF_8));
            }
        }
        if (!timeRange.isBlank()) {
            builder.append("&f_TPR=").append(URLEncoder.encode(timeRange, StandardCharsets.UTF_8));
        }
        if (!seniority.isEmpty()) {
            for (var s : seniority) {
                builder.append("&f_E=").append(URLEncoder.encode(s, StandardCharsets.UTF_8));
            }
        }
        if (!workType.isEmpty()) {
            for (var w : workType) {
                builder.append("&f_WT=").append(URLEncoder.encode(w, StandardCharsets.UTF_8));
            }
        }

        return URI.create(builder.toString());
    }

    private String fetchHtml(URI uri) {
        return fetchHtml(uri, false);
    }

    private String fetchHtml(URI uri, boolean allow404) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);

        var client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .defaultHeader("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                .defaultHeader("Accept-Charset", "UTF-8")
                .build();

        try {
            var response = client.get()
                    .uri(uri)
                    .retrieve()
                    .toEntity(byte[].class);

            var statusCode = response.getStatusCode().value();
            var body = response.getBody();
            if (body == null) {
                return "";
            }

            var html = new String(body, StandardCharsets.UTF_8);

            if (statusCode == 403 || statusCode == 429) {
                throw new ScraperException("LinkedIn returned HTTP " + statusCode + ": " + html);
            }
            if (statusCode == 404 && allow404) {
                return null;
            }
            if (statusCode >= 400) {
                throw new ScraperException("LinkedIn returned HTTP " + statusCode);
            }

            return html;
        } catch (ScraperException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            if (allow404) {
                return null;
            }
            throw new ScraperException("LinkedIn returned HTTP 404", e);
        } catch (Exception e) {
            throw new ScraperException("Failed to fetch HTML from " + uri, e);
        }
    }

    private List<RawJob> parseJobs(String html) {
        var document = Jsoup.parse(html, baseUrl);
        var cards = document.select(".base-search-card");
        var jobs = new ArrayList<RawJob>();

        for (var card : cards) {
            mapCardToRawJob(card).ifPresent(jobs::add);
        }

        if (jobs.isEmpty()) {
            log.debug("{}: no job cards found in search results", PROVIDER_ID);
        }

        return jobs;
    }

    private Optional<RawJob> mapCardToRawJob(Element card) {
        var linkElement = card.selectFirst(".base-card__full-link");
        String url;
        String jobId;

        if (linkElement != null && linkElement.hasAttr("href")) {
            url = linkElement.absUrl("href");
            jobId = extractJobIdFromUrl(url);
        } else {
            var searchId = card.attr("data-search-id");
            if (searchId.isEmpty()) {
                return Optional.empty();
            }
            jobId = "search-" + searchId;
            url = baseUrl + DETAIL_PATH_PREFIX + jobId;
        }

        if (jobId.isEmpty()) {
            return Optional.empty();
        }

        var titleElement = card.selectFirst(".base-search-card__title");
        var title = titleElement != null ? clean(titleElement.text()) : "";
        log.debug("{}: parsed title='{}' from card", PROVIDER_ID, title);
        if (title.isEmpty()) {
            return Optional.empty();
        }

        var companyElement = card.selectFirst(".base-search-card__subtitle");
        var company = companyElement != null ? clean(companyElement.text()) : "";

        var locationElement = card.selectFirst(".job-search-card__location");
        var location = locationElement != null ? clean(locationElement.text()) : "";

        var dateElement = card.selectFirst(".job-search-card__listdate");
        var rawDate = dateElement != null ? clean(dateElement.text()) : "";
        var dateTimeAttr = dateElement != null ? dateElement.attr("datetime") : "";

        var metadata = new HashMap<String, String>();
        metadata.put("jobId", jobId);
        if (!dateTimeAttr.isEmpty()) {
            metadata.put("postedDate", dateTimeAttr);
        }

        var description = extractSnippetFromCard(card);

        return Optional.of(new RawJob(
                title,
                company,
                url,
                description,
                rawDate,
                location,
                "",
                PROVIDER_ID,
                metadata
        ));
    }

    private String extractJobIdFromUrl(String url) {
        var matcher = JOB_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String extractSnippetFromCard(Element card) {
        var snippetParts = new ArrayList<String>();

        var locationElement = card.selectFirst(".job-search-card__location");
        if (locationElement != null) {
            snippetParts.add(clean(locationElement.text()));
        }

        var dateElement = card.selectFirst(".job-search-card__listdate");
        if (dateElement != null) {
            snippetParts.add(clean(dateElement.text()));
        }

        return String.join(" | ", snippetParts);
    }

    private List<RawJob> enrichWithDetailPages(List<RawJob> jobs) {
        var enrichedJobs = new ArrayList<RawJob>();

        for (var i = 0; i < jobs.size(); i++) {
            var job = jobs.get(i);
            var jobId = job.metadata().get("jobId");

            if (jobId == null || jobId.isEmpty() || jobId.startsWith("search-")) {
                enrichedJobs.add(job);
                continue;
            }

            var detailUrl = baseUrl + DETAIL_PATH_PREFIX + jobId;
            String detailHtml = null;

            try {
                detailHtml = fetchHtml(URI.create(detailUrl), true);
                if (detailHtml != null && isBotChallenge(detailHtml)) {
                    log.warn("{}: bot challenge detected on detail page for jobId={}, using list data", PROVIDER_ID, jobId);
                    detailHtml = null;
                }
            } catch (ScraperException e) {
                if (e.getMessage() != null && (e.getMessage().contains("403") || e.getMessage().contains("429"))) {
                    throw e;
                }
                log.warn("{}: failed to fetch detail page for jobId={}: {}, using list data", PROVIDER_ID, jobId, e.getMessage());
            } catch (Exception e) {
                log.warn("{}: failed to fetch detail page for jobId={}: {}, using list data", PROVIDER_ID, jobId, e.getMessage());
            }

            var enrichedJob = enrichJobWithDetail(job, detailHtml);
            enrichedJobs.add(enrichedJob);

            if (i < jobs.size() - 1 && detailFetchDelayMillis > 0) {
                try {
                    Thread.sleep(detailFetchDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("{}: detail fetch delay interrupted", PROVIDER_ID);
                }
            }
        }

        return enrichedJobs;
    }

    private RawJob enrichJobWithDetail(RawJob job, String detailHtml) {
        if (detailHtml == null || detailHtml.isBlank()) {
            return job;
        }

        var document = Jsoup.parse(detailHtml, baseUrl);

        var description = extractDescriptionFromDetail(document);
        var seniority = extractSeniorityFromDetail(document);
        var workType = extractWorkTypeFromDetail(document);

        var metadata = new HashMap<>(job.metadata());
        if (!seniority.isEmpty()) {
            metadata.put("seniority", seniority);
        }
        if (!workType.isEmpty()) {
            metadata.put("workType", workType);
        }
        if (!location.isBlank()) {
            metadata.put("locationFilter", location);
        }
        if (!geoIds.isEmpty()) {
            metadata.put("geoIds", String.join(",", geoIds));
        }
        if (!timeRange.isBlank()) {
            metadata.put("timeRange", timeRange);
        }

        return new RawJob(
                job.title(),
                job.company(),
                job.url(),
                description.isBlank() ? job.description() : description,
                job.rawDate(),
                job.location(),
                job.workModel(),
                job.source(),
                metadata
        );
    }

    private String extractDescriptionFromDetail(Document document) {
        var descriptionElement = document.selectFirst(".show-more-less-html__markup");
        if (descriptionElement != null) {
            return clean(descriptionElement.html());
        }
        return "";
    }

    private String extractSeniorityFromDetail(Document document) {
        var criteriaList = document.select(".description__job-criteria-list li");
        for (var item : criteriaList) {
            var header = item.selectFirst("h3");
            var value = item.selectFirst("span");
            if (header != null && value != null) {
                var headerText = clean(header.text()).toLowerCase(java.util.Locale.ROOT);
                if (headerText.contains("seniority") || headerText.contains("nível") || headerText.contains("nivel")) {
                    return clean(value.text());
                }
            }
        }
        return "";
    }

    private String extractWorkTypeFromDetail(Document document) {
        var criteriaList = document.select(".description__job-criteria-list li");
        for (var item : criteriaList) {
            var header = item.selectFirst("h3");
            var value = item.selectFirst("span");
            if (header != null && value != null) {
                var headerText = clean(header.text()).toLowerCase(java.util.Locale.ROOT);
                if (headerText.contains("employment") || headerText.contains("tipo") || headerText.contains("work type")) {
                    return clean(value.text());
                }
            }
        }
        return "";
    }

    private boolean isBotChallenge(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        var normalized = normalize(html);
        return normalized.contains("captcha")
                || normalized.contains("not a robot")
                || normalized.contains("não sou um robô")
                || normalized.contains("access denied")
                || normalized.contains("blocked")
                || normalized.contains("challenge")
                || normalized.contains("verification required");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        var cleaned = clean(value).toLowerCase(java.util.Locale.ROOT);
        var normalized = java.text.Normalizer.normalize(cleaned, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized;
    }

    private static String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}