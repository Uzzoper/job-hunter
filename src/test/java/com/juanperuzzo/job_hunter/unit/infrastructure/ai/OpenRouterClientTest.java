package com.juanperuzzo.job_hunter.unit.infrastructure.ai;

import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.infrastructure.ai.OpenRouterClient;
import com.juanperuzzo.job_hunter.infrastructure.scraper.retry.ExponentialBackoffRetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

class OpenRouterClientTest {

    private WireMockServer wireMockServer;
    private OpenRouterClient openRouterClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();

        openRouterClient = new OpenRouterClient(
                "http://localhost:8089",
                "test-api-key",
                "meta-llama/llama-3.3-70b-instruct:free",
                5
        );
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    @DisplayName("getCompletion when successful should return AI response text")
    void getCompletion_whenSuccessful_shouldReturnResponseText() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{
                                        "message": {
                                            "content": "This is the AI response"
                                        }
                                    }]
                                }
                                """)));

        String result = openRouterClient.getCompletion("Test prompt");

        assertEquals("This is the AI response", result);
    }

    @Test
    @DisplayName("getCompletion when HTTP 4xx/5xx should throw AiException")
    void getCompletion_whenHttpError_shouldThrowAiException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        assertThrows(AiException.class, () -> openRouterClient.getCompletion("Test prompt"));
    }

    @Test
    @DisplayName("getCompletion when timeout should throw AiException")
    @Timeout(value = 10)
    void getCompletion_whenTimeout_shouldThrowAiException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withFixedDelay(10000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{
                                        "message": {
                                            "content": "This is the AI response"
                                        }
                                    }]
                                }
                                """)));

        assertThrows(AiException.class, () -> openRouterClient.getCompletion("Test prompt"));
    }

    @Test
    @DisplayName("getCompletion when prompt has special characters should send valid JSON")
    void getCompletion_whenPromptHasSpecialChars_shouldSendValidJson() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.messages[0].content"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"choices": [{"message": {"content": "AI response"}}]}
                                """)));

        String result = openRouterClient.getCompletion("Line 1\nLine 2\tTabbed\"Quoted\\Backslash");
        assertEquals("AI response", result);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("meta-llama/llama-3.3-70b-instruct:free")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content")));
    }

    // ===== Retry with backoff (spec: retry-backoff-ai.md) =====

    private OpenRouterClient clientWithRetry() {
        return new OpenRouterClient(
                "http://localhost:8089",
                "test-api-key",
                "meta-llama/llama-3.3-70b-instruct:free",
                5,
                new ExponentialBackoffRetry(3, Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(0)));
    }

    private void stubSuccess(String scenario, String state) {
        var builder = post(urlEqualTo("/chat/completions"));
        if (scenario != null) {
            builder = builder.inScenario(scenario).whenScenarioStateIs(state);
        }
        wireMockServer.stubFor(builder.willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\": [{\"message\": {\"content\": \"AI response\"}}]}")));
    }

    @Test
    @DisplayName("complete should retry on 429 and succeed on a later attempt")
    @Timeout(value = 10)
    void complete_when429ThenSuccess_shouldRetryAndReturnCompletion() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"message\": \"rate limited\"}}"))
                .willSetStateTo("Recovered"));
        stubSuccess("retry", "Recovered");

        String result = clientWithRetry().getCompletion("Test prompt");

        assertEquals("AI response", result);
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("complete should throw AiException after retries are exhausted on persistent 429")
    @Timeout(value = 10)
    void complete_whenAlways429_shouldThrowAiExceptionAfterRetries() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"message\": \"rate limited\"}}")));

        assertThrows(AiException.class,
                () -> clientWithRetry().getCompletion("Test prompt"));
        wireMockServer.verify(3, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("complete should not retry on 401 invalid credentials")
    void complete_when401_shouldFailFastWithSingleCall() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"message\": \"invalid api key\"}}")));

        assertThrows(AiException.class,
                () -> clientWithRetry().getCompletion("Test prompt"));
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    @DisplayName("complete should retry when the first attempt times out")
    @Timeout(value = 15)
    void complete_whenTimeoutThenSuccess_shouldRetry() {
        // First attempt exceeds the 1s read timeout; second responds instantly.
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("timeout-retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withFixedDelay(3000))
                .willSetStateTo("Recovered"));
        stubSuccess("timeout-retry", "Recovered");

        var client = new OpenRouterClient(
                "http://localhost:8089",
                "test-api-key",
                "meta-llama/llama-3.3-70b-instruct:free",
                1,
                new ExponentialBackoffRetry(3, Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofMillis(0)));

        String result = client.getCompletion("Test prompt");

        assertEquals("AI response", result);
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }
}
