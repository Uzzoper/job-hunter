package com.juanperuzzo.job_hunter.infrastructure.email;

import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.juanperuzzo.job_hunter.domain.exception.EmailDeliveryException;
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
 * Email sender backed by a Hermes Agent bot. Instead of calling an email API
 * directly, the send request is delegated as an instruction to the Hermes Agent
 * gateway ("hermes serve", OpenAI-compatible chat completions); the bot performs
 * the actual delivery with the email tool configured in its profile (MCP, skill).
 */
public class HermesBotEmailSender implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(HermesBotEmailSender.class);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HermesBotEmailSender(String baseUrl, String apiKey, String model, int timeoutSeconds) {
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
    public void send(String from, String to, String subject, String body) {
        try {
            var requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "stream", false,
                    "messages", List.of(Map.of("role", "user", "content", buildInstruction(from, to, subject, body)))
            ));

            var responseBody = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> {
                                throw new EmailDeliveryException("Hermes API error: " + resp.getStatusCode());
                            })
                    .body(String.class);

            log.info("Hermes bot acknowledged email to {}: {}", to, extractReply(responseBody));
        } catch (ResourceAccessException e) {
            throw new EmailDeliveryException("Request to Hermes gateway timed out", e);
        } catch (EmailDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new EmailDeliveryException("Failed to delegate email sending to Hermes bot", e);
        }
    }

    private String buildInstruction(String from, String to, String subject, String body) {
        return """
                You are the application-email sender for Job Hunter. Use your email tool to \
                send exactly the following message. Do not modify, summarize or rewrite anything. \
                If you have no email tool available, reply with EMAIL_TOOL_MISSING.

                From: %s
                To: %s
                Subject: %s

                %s

                When the email has been sent, reply only with: EMAIL_SENT""".formatted(from, to, subject, body);
    }

    private String extractReply(String responseBody) {
        try {
            ChatCompletionResponse response = objectMapper.readValue(responseBody, ChatCompletionResponse.class);
            Choice choice = response.choices().get(0);
            if ("error".equalsIgnoreCase(choice.finishReason())) {
                // gateway answers well-formed 200s when the upstream provider fails
                throw new EmailDeliveryException("Hermes bot failed: " + choice.message().content());
            }
            return choice.message().content();
        } catch (EmailDeliveryException e) {
            throw e;
        } catch (Exception e) {
            return "<unparseable response>";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
}
