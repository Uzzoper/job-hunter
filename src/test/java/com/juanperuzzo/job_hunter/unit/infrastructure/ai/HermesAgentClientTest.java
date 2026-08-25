package com.juanperuzzo.job_hunter.unit.infrastructure.ai;

import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

class HermesAgentClientTest {

    private WireMockServer wireMockServer;
    private HermesAgentClient hermesAgentClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(8090);
        wireMockServer.start();

        hermesAgentClient = new HermesAgentClient(
                "http://localhost:8090",
                "test-hermes-key",
                "default",
                5
        );
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    @DisplayName("complete when successful should return AI response text")
    void complete_whenSuccessful_shouldReturnResponseText() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{
                                        "message": {
                                            "content": "This is the Hermes response"
                                        }
                                    }]
                                }
                                """)));

        String result = hermesAgentClient.complete("Test prompt");

        assertEquals("This is the Hermes response", result);
    }

    @Test
    @DisplayName("complete when HTTP 4xx/5xx should throw AiException")
    void complete_whenHttpError_shouldThrowAiException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        assertThrows(AiException.class, () -> hermesAgentClient.complete("Test prompt"));
    }

    @Test
    @DisplayName("complete when timeout should throw AiException")
    @Timeout(value = 10)
    void complete_whenTimeout_shouldThrowAiException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withFixedDelay(10000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{
                                        "message": {
                                            "content": "This is the Hermes response"
                                        }
                                    }]
                                }
                                """)));

        assertThrows(AiException.class, () -> hermesAgentClient.complete("Test prompt"));
    }

    @Test
    @DisplayName("complete when prompt has special characters should send valid JSON")
    void complete_whenPromptHasSpecialChars_shouldSendValidJson() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.messages[0].content"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"choices": [{"message": {"content": "AI response"}}]}
                                """)));

        String result = hermesAgentClient.complete("Line 1\nLine 2\tTabbed\"Quoted\\Backslash");
        assertEquals("AI response", result);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("default")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content")));
    }

    @Test
    @DisplayName("complete should send Authorization header with bearer key")
    void complete_shouldSendAuthorizationHeader() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"choices": [{"message": {"content": "response"}}]}
                                """)));

        hermesAgentClient.complete("Test prompt");

        wireMockServer.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-hermes-key")));
    }
}
