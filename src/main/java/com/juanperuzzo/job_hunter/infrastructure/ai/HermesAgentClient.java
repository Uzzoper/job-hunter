package com.juanperuzzo.job_hunter.infrastructure.ai;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AI client backed by the Hermes Agent gateway ("hermes gateway"), which exposes an
 * OpenAI-compatible chat completions endpoint on localhost:9119 by default.
 */
public class HermesAgentClient implements AiPort {

    private static final Logger log = LoggerFactory.getLogger(HermesAgentClient.class);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HermesAgentClient(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.model = model;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();
    }

    @Override
    public String complete(String prompt) {
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
                                    log.warn("Failed to read Hermes error response body", e);
                                }
                                throw new AiException("Hermes HTTP error: " + resp.getStatusCode() +
                                        (body != null && !body.isBlank() ? " " + body : ""));
                            })
                    .body(String.class);

            return extractText(responseBody);
        } catch (ResourceAccessException e) {
            throw new AiException("Hermes request timed out", e);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to get completion from Hermes", e);
        }
    }

    private String buildRequest(String prompt) {
        try {
            var messages = List.of(Map.of("role", "user", "content", prompt));
            var body = Map.of("model", model, "stream", false, "messages", messages);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AiException("Failed to build Hermes request body", e);
        }
    }

    private String extractText(String responseBody) {
        try {
            ChatCompletionResponse response = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            Choice choice = response.choices().get(0);
            if ("error".equalsIgnoreCase(choice.finishReason())) {
                // gateway answers well-formed 200s when the upstream provider fails
                throw new AiException("Hermes returned an embedded error: " + choice.message().content());
            }
            return choice.message().content();
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to parse Hermes response", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
}
