package com.juanperuzzo.job_hunter.infrastructure.email;

import com.juanperuzzo.job_hunter.application.port.out.EmailSenderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class ResendEmailSender implements EmailSenderPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResendEmailSender(String baseUrl, String apiKey, int timeoutSeconds) {
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
                    "from", from,
                    "to", List.of(to),
                    "subject", subject,
                    "text", body
            ));

            restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> {
                                throw new RuntimeException("Resend API error: " + resp.getStatusCode());
                            })
                    .toBodilessEntity();
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email via Resend", e);
        }
    }
}
