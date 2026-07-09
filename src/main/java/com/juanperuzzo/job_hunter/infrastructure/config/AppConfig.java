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
import com.juanperuzzo.job_hunter.application.port.out.PasswordHasher;
import com.juanperuzzo.job_hunter.application.port.out.TokenProvider;
import com.juanperuzzo.job_hunter.application.port.out.UserRepository;
import com.juanperuzzo.job_hunter.application.port.out.JobAnalysisRepository;
import com.juanperuzzo.job_hunter.application.port.out.UserProfileRepository;
import com.juanperuzzo.job_hunter.application.service.AuthService;
import com.juanperuzzo.job_hunter.application.service.UserProfileService;
import com.juanperuzzo.job_hunter.infrastructure.security.JwtTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.juanperuzzo.job_hunter.infrastructure.scraper.ProviderBasedScraperAdapter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.DateParser;
import com.juanperuzzo.job_hunter.infrastructure.scraper.normalizer.JobNormalizer;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.GupyProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.InfoJobsProvider;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.ProviderRegistry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.RateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.ratelimit.TokenBucketRateLimiter;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.ExtractionStrategy;

@Configuration
public class AppConfig {

    // ═══════════════════════════════════════════
    //  Legacy scrapers (kept for migration)
    // ═══════════════════════════════════════════

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
            @Value("${scraper.infojobs.timeout-seconds}") int timeoutSeconds) {
        return new InfoJobsScraper(baseUrl, enabled, keywords, excludeKeywords, locations, maxPages,
                30, timeoutSeconds, 0);
    }

    @Bean
    // @Primary (moved to provider-based)
    public ScraperPort scraperPort(GupyScraper gupyScraper, InfoJobsScraper infoJobsScraper) {
        return new CompositeScraper(List.of(gupyScraper, infoJobsScraper));
    }

    // ═══════════════════════════════════════════
    //  Provider-based scraper beans
    // ═══════════════════════════════════════════

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
            ExponentialBackoffRetry exponentialBackoffRetry,
            RateLimiter rateLimiter,
            JobNormalizer jobNormalizer) {
        var registry = new ProviderRegistry();
        registry.register(gupyProvider, exponentialBackoffRetry, rateLimiter, jobNormalizer);
        registry.register(infojobsProvider, exponentialBackoffRetry, rateLimiter, jobNormalizer);
        return registry;
    }

    @Bean
    @Primary
    public ProviderBasedScraperAdapter providerBasedScraperAdapter(ProviderRegistry providerRegistry) {
        return new ProviderBasedScraperAdapter(providerRegistry);
    }

    // ═══════════════════════════════════════════
    //  AI, Security, and Services
    // ═══════════════════════════════════════════

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
    public AiAnalysisService aiAnalysisService(AiPort aiPort, JobAnalysisRepository jobAnalysisRepository,
                                               UserProfileRepository userProfileRepository, JobRepository jobRepository) {
        return new AiAnalysisService(aiPort, jobAnalysisRepository, userProfileRepository, jobRepository);
    }

    @Bean
    public EmailGenerationService emailGenerationService(AiPort aiPort, EmailDraftRepository emailDraftRepository,
                                                         UserProfileRepository userProfileRepository,
                                                         JobRepository jobRepository, JobAnalysisRepository jobAnalysisRepository) {
        return new EmailGenerationService(aiPort, emailDraftRepository, userProfileRepository, jobRepository, jobAnalysisRepository);
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
}
