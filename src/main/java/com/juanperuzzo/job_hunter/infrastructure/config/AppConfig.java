package com.juanperuzzo.job_hunter.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.service.AiAnalysisService;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
import com.juanperuzzo.job_hunter.application.service.FetchJobsService;
import com.juanperuzzo.job_hunter.infrastructure.ai.OpenRouterClient;
import com.juanperuzzo.job_hunter.infrastructure.scraper.GupyScraper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AppConfig {

    @Bean
    public GupyScraper gupyScraper(
            @Value("${scraper.gupy.base-url}") String baseUrl,
            @Value("#{'${scraper.gupy.keywords}'.split(',')}") List<String> keywords,
            @Value("${scraper.gupy.limit}") int limit,
            @Value("${scraper.gupy.timeout-seconds}") int timeoutSeconds) {
        return new GupyScraper(baseUrl, keywords, limit, timeoutSeconds);
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
