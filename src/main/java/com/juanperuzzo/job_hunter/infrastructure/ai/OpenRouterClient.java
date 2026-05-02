package com.juanperuzzo.job_hunter.infrastructure.ai;

import com.juanperuzzo.job_hunter.application.port.out.AiPort;
import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.List;

public class OpenRouterClient implements AiPort {

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenRouterClient(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
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
                            (req, resp) -> { throw new AiException("HTTP error: " + resp.getStatusCode()); })
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
        return String.format("""
                {"model": "%s", "messages": [{"role": "user", "content": "%s"}]}
                """, model, prompt.replace("\"", "\\\""));
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
    private static class ChatCompletionResponse {
        @JsonProperty("choices")
        private List<Choice> choices;
        public List<Choice> choices() { return choices; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Choice {
        @JsonProperty("message")
        private Message message;
        public Message message() { return message; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Message {
        @JsonProperty("content")
        private String content;
        public String content() { return content; }
    }
}
