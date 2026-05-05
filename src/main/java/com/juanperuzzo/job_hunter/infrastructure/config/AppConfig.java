package com.juanperuzzo.job_hunter.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.service.AiAnalysisService;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
import com.juanperuzzo.job_hunter.application.service.FetchJobsService;
import com.juanperuzzo.job_hunter.infrastructure.ai.OpenRouterClient;
import com.juanperuzzo.job_hunter.infrastructure.scraper.CompositeScraper;
import com.juanperuzzo.job_hunter.infrastructure.scraper.GupyScraper;
import com.juanperuzzo.job_hunter.infrastructure.scraper.InfoJobsScraper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AppConfig {

    @Bean
    public GupyScraper gupyScraper(
            @Value("${scraper.gupy.base-url}") String baseUrl,
            @Value("#{'${scraper.gupy.keywords}'.split(',')}") List<String> keywords,
            @Value("#{'${scraper.gupy.exclude-keywords}'.split(',')}") List<String> excludeKeywords,
            @Value("#{'${scraper.gupy.locations}'.split(',')}") List<String> locations,
            @Value("${scraper.gupy.limit}") int limit,
            @Value("${scraper.gupy.timeout-seconds}") int timeoutSeconds) {
        return new GupyScraper(baseUrl, keywords, excludeKeywords, locations, limit, timeoutSeconds);
    }

    @Bean
    public InfoJobsScraper infoJobsScraper(
            @Value("${scraper.infojobs.base-url}") String baseUrl,
            @Value("${scraper.infojobs.enabled}") boolean enabled,
            @Value("#{'${scraper.infojobs.keywords}'.split(',')}") List<String> keywords,
            @Value("#{'${scraper.infojobs.exclude-keywords}'.split(',')}") List<String> excludeKeywords,
            @Value("#{'${scraper.infojobs.locations}'.split(',')}") List<String> locations,
            @Value("${scraper.infojobs.max-pages}") int maxPages,
            @Value("${scraper.infojobs.max-age-days}") int maxAgeDays,
            @Value("${scraper.infojobs.timeout-seconds}") int timeoutSeconds,
            @Value("${scraper.infojobs.delay-millis}") long delayMillis) {
        return new InfoJobsScraper(baseUrl, enabled, keywords, excludeKeywords, locations, maxPages, maxAgeDays,
                timeoutSeconds, delayMillis);
    }

    @Bean
    @Primary
    public ScraperPort scraperPort(GupyScraper gupyScraper, InfoJobsScraper infoJobsScraper) {
        return new CompositeScraper(List.of(gupyScraper, infoJobsScraper));
    }

    @Bean
    public OpenRouterClient openRouterClient(
            @Value("${ai.openrouter.base-url}") String baseUrl,
            @Value("${ai.openrouter.api-key}") String apiKey,
            @Value("${ai.openrouter.model}") String model,
            @Value("${ai.openrouter.timeout-seconds}") int timeoutSeconds) {
        return new OpenRouterClient(baseUrl, apiKey, model, timeoutSeconds);
    }

    @Bean
    public FetchJobsService fetchJobsService(ScraperPort scraperPort, JobRepository jobRepository) {
        return new FetchJobsService(scraperPort, jobRepository);
    }

    @Bean
    public AiAnalysisService aiAnalysisService(AiPort aiPort) {
        return new AiAnalysisService(aiPort);
    }

    @Bean
    public EmailGenerationService emailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository) {
        return new EmailGenerationService(aiPort, emailDraftRepository);
    }
}
