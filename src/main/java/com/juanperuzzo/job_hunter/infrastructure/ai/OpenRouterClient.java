package com.juanperuzzo.job_hunter.infrastructure.ai;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.domain.exception.ScraperException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;

public class OpenRouterClient implements AiPort {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

    private final RestClient restClient;
    private final String model;
    private final ExponentialBackoffRetry retryPolicy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param retryPolicy optional retry policy; when present, transient failures
     *                    (HTTP 429/5xx, network timeouts) are retried with
     *                    exponential backoff before surfacing an {@link AiException}.
     */
    public OpenRouterClient(String baseUrl, String apiKey, String model, int timeoutSeconds, ExponentialBackoffRetry retryPolicy) {
        this.model = model;
        this.retryPolicy = retryPolicy;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
    }

    public OpenRouterClient(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this(baseUrl, apiKey, model, timeoutSeconds, null);
    }

    @Override
    public String complete(String prompt) {
        if (retryPolicy == null) {
            return executeCompletion(prompt);
        }
        try {
            return retryPolicy.execute(() -> executeCompletion(prompt));
        } catch (ScraperException e) {
            // Translate so AiPort callers keep their exception semantics.
            throw new AiException("AI call failed after retries", e);
        }
    }

    private String executeCompletion(String prompt) {
        try {
            String requestBody = buildRequest(prompt);

            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> {
                                String body = "";
                                try {
                                    body = new String(resp.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                } catch (Exception e) {
                                    log.warn("Failed to read AI error response body", e);
                                }
                                throw new AiException("HTTP error: " + resp.getStatusCode() +
                                        (body != null && !body.isBlank() ? " " + body : ""));
                            })
                    .body(String.class);

            return extractText(responseBody);
        } catch (ResourceAccessException e) {
            throw new AiException("Request timed out", e);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to get completion", e);
        }
    }

    public String getCompletion(String prompt) {
        return complete(prompt);
    }

    private String buildRequest(String prompt) {
        try {
            var messages = List.of(Map.of("role", "user", "content", prompt));
            var body = Map.of("model", model, "messages", messages);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AiException("Failed to build request body", e);
        }
    }

    private String extractText(String responseBody) {
        try {
            ChatCompletionResponse response = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            return response.choices().get(0).message().content();
        } catch (Exception e) {
            throw new AiException("Failed to parse AI response", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
}
