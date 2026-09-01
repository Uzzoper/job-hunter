package com.juanperuzzo.job_hunter.infrastructure.config;

import com.juanperuzzo.job_hunter.application.port.in.AutoSendEligibilityUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.application.port.out.EmailDraftRepository;
import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.juanperuzzo.job_hunter.application.port.out.JobRepository;
import com.juanperuzzo.job_hunter.application.port.out.CompanySiteEnrichmentPort;
import com.juanperuzzo.job_hunter.application.port.out.NormalizerPort;
import com.juanperuzzo.job_hunter.application.port.out.PdfRendererPort;
import com.juanperuzzo.job_hunter.application.port.out.ScraperPort;
import com.juanperuzzo.job_hunter.application.port.out.SourceFetchPort;
import com.juanperuzzo.job_hunter.application.service.AiAnalysisService;
import com.juanperuzzo.job_hunter.application.service.EmailGenerationService;
import com.juanperuzzo.job_hunter.application.service.EmailSendingService;
import com.juanperuzzo.job_hunter.application.service.FetchJobsService;
import com.juanperuzzo.job_hunter.application.service.FetchSourceJobsService;
import com.juanperuzzo.job_hunter.application.service.ResumeTailoringService;
import com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient;
import com.juanperuzzo.job_hunter.infrastructure.ai.OllamaClient;
import com.juanperuzzo.job_hunter.infrastructure.ai.OpenRouterClient;
import com.juanperuzzo.job_hunter.infrastructure.email.HermesBotEmailSender;
import com.juanperuzzo.job_hunter.infrastructure.pdf.ResumePdfRenderer;
import com.juanperuzzo.job_hunter.infrastructure.scheduler.AutoSendScheduler;
import com.juanperuzzo.job_hunter.application.port.out.PasswordHasher;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.ApproveDraftService;
import com.juanperuzzo.job_hunter.application.service.AuthService;
import com.juanperuzzo.job_hunter.application.service.AutoSendEligibilityService;
import com.juanperuzzo.job_hunter.application.service.ResumeUploadService;
import com.juanperuzzo.job_hunter.application.service.TemplateEmailService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenService;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.juanperuzzo.job_hunter.infrastructure.scraper.adapter.ProviderBasedScraperAdapter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.enricher.CompanySiteEnricher;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.client.LinkedInScraperClient;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.InfoJobsProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.LinkedInProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;

@Configuration
@EnableConfigurationProperties(LinkedInScraperProperties.class)
@EnableScheduling
public class AppConfig {

    @Bean
    public ExponentialBackoffRetry exponentialBackoffRetry(
            @Value("${scraper.retry.max-attempts}") int maxAttempts,
            @Value("${scraper.retry.base-delay-millis}") long baseDelayMillis,
            @Value("${scraper.retry.max-delay-millis}") long maxDelayMillis,
            @Value("${scraper.retry.max-jitter-millis}") long maxJitterMillis) {
        return new ExponentialBackoffRetry(
                maxAttempts,
                Duration.ofMillis(baseDelayMillis),
                Duration.ofMillis(maxDelayMillis),
                Duration.ofMillis(maxJitterMillis));
    }

    @Bean
    public RateLimiter rateLimiter(
            @Value("${scraper.rate-limiter.default-permits-per-second}") double permitsPerSecond,
            @Value("${scraper.rate-limiter.default-burst}") int burst) {
        return new TokenBucketRateLimiter(permitsPerSecond, burst, java.util.Map.of());
    }

    @Bean
    public DateParser dateParser() {
        return new DateParser(Clock.systemUTC());
    }

    /**
     * Shared {@link RestClient} used by scraping infra (connection timeouts from
     * {@code scraper.rest-client.timeout-seconds}). Providers may keep their own
     * per-call clients; this bean serves the company-site enricher and future use.
     */
    @Bean
    public RestClient scraperRestClient(
            @Value("${scraper.rest-client.timeout-seconds:10}") int timeoutSeconds) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutSeconds * 1000);
        requestFactory.setReadTimeout(timeoutSeconds * 1000);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("User-Agent", "JobHunter/1.0")
                .build();
    }

    /**
     * Per-domain rate limiter for the company-site enricher (1 req/s per domain,
     * burst 1) — email-enrichment spec Scenario 14. Distinct from the provider-wide
     * {@link RateLimiter} bean so enricher throttling never interferes with provider calls.
     */
    @Bean
    public RateLimiter enricherRateLimiter(
            @Value("${scraper.enricher.per-domain-permits-per-second:1}") double permitsPerSecond) {
        return new TokenBucketRateLimiter(permitsPerSecond, 1, java.util.Map.of());
    }

    @Bean
    public CompanySiteEnricher companySiteEnricher(
            RestClient scraperRestClient,
            ExponentialBackoffRetry exponentialBackoffRetry,
            @Qualifier("enricherRateLimiter") RateLimiter enricherRateLimiter,
            @Value("${scraper.enricher.enabled:true}") boolean enabled,
            @Value("${scraper.enricher.concurrency:2}") int concurrency,
            @Value("${scraper.enricher.max-pages-per-company:2}") int maxPages,
            @Value("${scraper.enricher.cache-ttl-hours:24}") int cacheTtlHours,
            @Value("#{'${scraper.enricher.contact-paths:/contato,/contact,/trabalhe-conosco,/carreiras}'.split(',')}") List<String> contactPaths) {
        return new CompanySiteEnricher(
                scraperRestClient,
                exponentialBackoffRetry,
                enricherRateLimiter,
                enabled,
                concurrency,
                maxPages,
                Duration.ofHours(cacheTtlHours),
                contactPaths);
    }

    @Bean
    public JobNormalizer jobNormalizer(
            DateParser dateParser,
            @Value("#{'${scraper.gupy.keywords}'.split(',')}") List<String> keywords,
            @Value("${scraper.normalizer.max-age-days}") int maxAgeDays) {
        var excludePatterns = List.of(
                Pattern.compile("(?i)\\b(s[eê]nior|senior|sr\\.?|especialista|lead|coordenador|manager|bdr)\\b"));
        return new JobNormalizer(dateParser, keywords, excludePatterns, List.of(), maxAgeDays, Clock.systemUTC());
    }

    @Bean
    public JobNormalizer linkedinJobNormalizer(
            DateParser dateParser,
            @Value("#{'${scraper.linkedin.keywords}'.split(',')}") List<String> keywords,
            @Value("${scraper.normalizer.max-age-days}") int maxAgeDays) {
        var excludePatterns = List.of(
                Pattern.compile("(?i)\\b(s[eê]nior|senior|sr\\.?|especialista|lead|coordenador|manager|bdr)\\b"));
        return new JobNormalizer(dateParser, keywords, excludePatterns, List.of(), maxAgeDays, Clock.systemUTC());
    }

    @Bean
    @Primary
    public NormalizerPort normalizerPort(JobNormalizer jobNormalizer) {
        return jobNormalizer;
    }

    @Bean
    public NormalizerPort linkedinNormalizerPort(JobNormalizer linkedinJobNormalizer) {
        return linkedinJobNormalizer;
    }

    @Bean
    @Qualifier("linkedinProvider")
    @ConditionalOnProperty(name = "scraper.linkedin.mode", havingValue = "jsoup", matchIfMissing = true)
    public ExtractionStrategy linkedinProvider(
            LinkedInScraperProperties props,
            ExponentialBackoffRetry exponentialBackoffRetry) {
        return new LinkedInProvider(
                props,
                exponentialBackoffRetry);
    }

    @Bean
    @Qualifier("linkedinScraperClient")
    @ConditionalOnProperty(name = "scraper.linkedin.mode", havingValue = "service")
    public ExtractionStrategy linkedinScraperClient(LinkedInScraperProperties props) {
        return new LinkedInScraperClient(props);
    }

    @Bean
    public ExtractionStrategy gupyProvider(
            @Value("${scraper.gupy.base-url}") String baseUrl,
            @Value("${scraper.gupy.timeout-seconds}") int timeoutSeconds,
            @Value("#{'${scraper.gupy.keywords}'.split(',')}") List<String> keywords,
            @Value("${scraper.gupy.limit}") int limit,
            ExponentialBackoffRetry exponentialBackoffRetry) {
        return new GupyProvider(baseUrl, timeoutSeconds, keywords, limit, exponentialBackoffRetry);
    }

    @Bean
    public ExtractionStrategy infojobsProvider(
            @Value("${scraper.infojobs.base-url}") String baseUrl,
            @Value("${scraper.infojobs.timeout-seconds}") int timeoutSeconds,
            @Value("#{'${scraper.infojobs.keywords}'.split(',')}") List<String> keywords,
            @Value("${scraper.infojobs.max-pages}") int maxPages,
            ExponentialBackoffRetry exponentialBackoffRetry) {
        return new InfoJobsProvider(baseUrl, timeoutSeconds, keywords, maxPages, exponentialBackoffRetry);
    }

    @Bean
    public ProviderRegistry providerRegistry(
            ExtractionStrategy gupyProvider,
            ExtractionStrategy infojobsProvider,
            @Qualifier("linkedinProvider") Optional<ExtractionStrategy> linkedinProvider,
            @Qualifier("linkedinScraperClient") Optional<ExtractionStrategy> linkedinScraperClient,
            ExponentialBackoffRetry exponentialBackoffRetry,
            RateLimiter rateLimiter,
            JobNormalizer jobNormalizer,
            JobNormalizer linkedinJobNormalizer) {
        var registry = new ProviderRegistry();
        registry.register(gupyProvider, exponentialBackoffRetry, rateLimiter, jobNormalizer);
        registry.register(infojobsProvider, exponentialBackoffRetry, rateLimiter, jobNormalizer);
        linkedinProvider.ifPresent(provider ->
                registry.register(provider, exponentialBackoffRetry, rateLimiter, linkedinJobNormalizer));
        linkedinScraperClient.ifPresent(provider ->
                registry.register(provider, exponentialBackoffRetry, rateLimiter, linkedinJobNormalizer));
        return registry;
    }

    @Bean
    public SourceFetchPort sourceFetchPort(ProviderRegistry providerRegistry) {
        return providerRegistry;
    }

    @Bean
    @Primary
    public ProviderBasedScraperAdapter providerBasedScraperAdapter(
            ProviderRegistry providerRegistry,
            CompanySiteEnricher companySiteEnricher) {
        return new ProviderBasedScraperAdapter(providerRegistry, companySiteEnricher);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "openrouter", matchIfMissing = true)
    public OpenRouterClient openRouterClient(
            @Value("${ai.openrouter.base-url}") String baseUrl,
            @Value("${ai.openrouter.api-key}") String apiKey,
            @Value("${ai.openrouter.model}") String model,
            @Value("${ai.openrouter.timeout-seconds}") int timeoutSeconds,
            ExponentialBackoffRetry exponentialBackoffRetry) {
        return new OpenRouterClient(baseUrl, apiKey, model, timeoutSeconds, exponentialBackoffRetry);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "ollama")
    public OllamaClient ollamaClient(
            @Value("${ai.ollama.base-url}") String baseUrl,
            @Value("${ai.ollama.model}") String model,
            @Value("${ai.ollama.timeout-seconds}") int timeoutSeconds) {
        return new OllamaClient(baseUrl, model, timeoutSeconds);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "hermes")
    public HermesAgentClient hermesAgentClient(
            @Value("${hermes.base-url}") String baseUrl,
            @Value("${hermes.api-key}") String apiKey,
            @Value("${hermes.model}") String model,
            @Value("${hermes.timeout-seconds}") int timeoutSeconds) {
        return new HermesAgentClient(baseUrl, apiKey, model, timeoutSeconds);
    }

    @Bean
    public FetchJobsService fetchJobsService(ScraperPort scraperPort, JobRepository jobRepository) {
        return new FetchJobsService(scraperPort, jobRepository);
    }

    @Bean
    public FetchSourceJobsService fetchSourceJobsService(SourceFetchPort sourceFetchPort, JobRepository jobRepository, @Qualifier("linkedinNormalizerPort") NormalizerPort normalizerPort, CompanySiteEnricher companySiteEnricher) {
        return new FetchSourceJobsService(sourceFetchPort, jobRepository, normalizerPort, companySiteEnricher);
    }

    @Bean
    public AiAnalysisService aiAnalysisService(AiPort aiPort, JobAnalysisRepository jobAnalysisRepository,
                                               UserProfileRepository userProfileRepository, JobRepository jobRepository) {
        return new AiAnalysisService(aiPort, jobAnalysisRepository, userProfileRepository, jobRepository);
    }

    @Bean
    public EmailGenerationService emailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository,
                                                         UserProfileRepository userProfileRepository,
                                                         JobRepository jobRepository, JobAnalysisRepository jobAnalysisRepository,
                                                         TemplateEmailService templateEmailService,
                                                         @Value("${email.standard-template.min-match-score:60}") int minMatchScore) {
        return new EmailGenerationService(aiPort, emailDraftRepository, userProfileRepository, jobRepository,
                jobAnalysisRepository, templateEmailService, minMatchScore);
    }

    @Bean
    public PasswordHasher passwordHasher(PasswordEncoder passwordEncoder) {
        return new PasswordHasher() {
            @Override
            public String hash(String rawPassword) {
                return passwordEncoder.encode(rawPassword);
            }

            @Override
            public boolean matches(String rawPassword, String hash) {
                return passwordEncoder.matches(rawPassword, hash);
            }
        };
    }

    @Bean
    public JwtTokenService jwtTokenService(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-hours}") int expirationHours) {
        return new JwtTokenService(secret, expirationHours);
    }

    @Bean
    public AuthService authService(UserRepository userRepository, PasswordHasher passwordHasher, TokenProvider tokenProvider) {
        return new AuthService(userRepository, passwordHasher, tokenProvider);
    }

    @Bean
    public UserProfileService userProfileService(UserRepository userRepository, UserProfileRepository userProfileRepository) {
        return new UserProfileService(userRepository, userProfileRepository);
    }

    @Bean
    public ResumeUploadService resumeUploadService(AiPort aiPort, UserProfileService userProfileService,
                                                   UserProfileRepository userProfileRepository,
                                                   @Value("${app.upload-dir}") String uploadDir,
                                                   @Value("${ai.resume-extraction.max-chars:8000}") int maxAiChars) {
        return new ResumeUploadService(aiPort, userProfileService, userProfileRepository, uploadDir, maxAiChars);
    }

    @Bean
    public HermesBotEmailSender hermesBotEmailSender(
            @Value("${hermes.base-url}") String baseUrl,
            @Value("${hermes.api-key}") String apiKey,
            @Value("${hermes.model}") String model,
            @Value("${hermes.timeout-seconds}") int timeoutSeconds) {
        return new HermesBotEmailSender(baseUrl, apiKey, model, timeoutSeconds);
    }

    @Bean
    public EmailSendingService emailSendingService(
            EmailDraftRepository emailDraftRepository,
            JobRepository jobRepository,
            UserRepository userRepository,
            EmailSenderPort emailSenderPort) {
        return new EmailSendingService(emailDraftRepository, jobRepository, userRepository, emailSenderPort);
    }

    @Bean
    public AutoSendEligibilityService autoSendEligibilityService(
            EmailDraftRepository emailDraftRepository,
            JobRepository jobRepository,
            JobAnalysisRepository jobAnalysisRepository,
            @Value("${auto-send.require-review}") boolean requireReview,
            @Value("${auto-send.daily-cap}") int dailyCap) {
        return new AutoSendEligibilityService(emailDraftRepository, jobRepository, jobAnalysisRepository, requireReview, dailyCap);
    }

    @Bean
    public TemplateEmailService templateEmailService() {
        return new TemplateEmailService();
    }

    @Bean
    public AutoSendScheduler autoSendScheduler(
            AutoSendEligibilityUseCase eligibilityPort,
            SendEmailUseCase sendEmailUseCase,
            @Value("${auto-send.enabled}") boolean enabled,
            @Value("${auto-send.jitter-seconds}") int jitterSeconds) {
        return new AutoSendScheduler(eligibilityPort, sendEmailUseCase, enabled, jitterSeconds);
    }

    @Bean
    public ResumePdfRenderer resumePdfRenderer() {
        return new ResumePdfRenderer();
    }

    @Bean
    public ResumeTailoringService resumeTailoringService(
            AiPort aiPort,
            JobRepository jobRepository,
            JobAnalysisRepository jobAnalysisRepository,
            UserProfileRepository userProfileRepository,
            UserRepository userRepository,
            PdfRendererPort pdfRendererPort,
            @Value("${ai.resume-tailoring.max-chars:8000}") int maxAiChars) {
        return new ResumeTailoringService(aiPort, jobRepository, jobAnalysisRepository,
                userProfileRepository, userRepository, pdfRendererPort, maxAiChars);
    }

    @Bean
    public ApproveDraftService approveDraftService(EmailDraftRepository emailDraftRepository) {
        return new ApproveDraftService(emailDraftRepository);
    }
}
