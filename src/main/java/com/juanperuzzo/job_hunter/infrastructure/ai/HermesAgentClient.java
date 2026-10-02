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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI client backed by the Hermes Agent gateway ("hermes gateway"), which exposes an
 * OpenAI-compatible chat completions endpoint on localhost:9119 by default.
 */
public class HermesAgentClient implements AiPort {

    private static final Logger log = LoggerFactory.getLogger(HermesAgentClient.class);

    /** Block tags reasoning models wrap their deliberation in; their content is never payload. */
    private static final List<String> REASONING_TAGS = List.of(
            "think", "thinking", "thought", "reasoning", "reflection", "scratchpad");

    /** A markdown quote line, e.g. {@code > weighing the requirements}. */
    private static final Pattern QUOTE_LINE = Pattern.compile("^>+\\s?.*");

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
                // checked before any stripping so the raw upstream message survives in the exception
                throw new AiException("Hermes returned an embedded error: " + choice.message().content());
            }
            return stripReasoning(choice.message().content());
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to parse Hermes response", e);
        }
    }

    /**
     * Removes model deliberation from a completion so consumers only see the requested payload.
     *
     * <p>Reasoning models emit their thinking either inside tagged blocks ({@code <think>},
     * {@code <thinking>}, {@code <thought>}, {@code <reasoning>}, {@code <reflection>},
     * {@code <scratchpad>}) or as a leading markdown quote block. Tagged blocks are dropped wherever
     * they appear; an unterminated opener drops everything from that point on, because whatever
     * follows is reasoning rather than payload. A markdown quote is only dropped while it is the
     * leading block, so a quoted line inside a payload survives.
     */
    private String stripReasoning(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        var stripped = content;
        for (var tag : REASONING_TAGS) {
            stripped = replaceDelimitedBlock(stripped, tag);
        }
        return stripLeadingQuoteBlock(stripped).strip();
    }

    /** Replaces every {@code <tag>...}</tag> block, plus any unterminated {@code <tag>...} tail. */
    private String replaceDelimitedBlock(String text, String tag) {
        var result = text;
        int from;
        while ((from = indexOfIgnoreCase(result, "<" + tag + ">")) >= 0) {
            int close = indexOfIgnoreCase(result, "</" + tag + ">", from + 1);
            if (close < 0) {
                return result.substring(0, from).strip();
            }
            result = result.substring(0, from) + result.substring(close + tag.length() + 3);
        }
        return result;
    }

    /** Drops a leading run of markdown quote lines (the deliberation block reasoning models emit). */
    private String stripLeadingQuoteBlock(String text) {
        int index = 0;
        int lastQuoteLineEnd = 0;
        while (index < text.length()) {
            int lineEnd = text.indexOf('\n', index);
            String line = lineEnd < 0 ? text.substring(index) : text.substring(index, lineEnd);
            if (line.isBlank() || !QUOTE_LINE.matcher(line).find()) {
                break;
            }
            lastQuoteLineEnd = lineEnd < 0 ? text.length() : lineEnd + 1;
            index = lastQuoteLineEnd;
        }
        return lastQuoteLineEnd == 0 ? text : text.substring(lastQuoteLineEnd).stripLeading();
    }

    private int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle);
    }

    private int indexOfIgnoreCase(String text, String needle, int fromIndex) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle, fromIndex);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {}
}
