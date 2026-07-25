package com.juanperuzzo.job_hunter.unit.infrastructure.ai;

import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.infrastructure.ai.OllamaClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

class OllamaClientTest {

    private WireMockServer wireMockServer;
    private OllamaClient ollamaClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(8088);
        wireMockServer.start();

        ollamaClient = new OllamaClient(
                "http://localhost:8088",
                "llama3.2",
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
                                            "content": "This is the Ollama response"
                                        }
                                    }]
                                }
                                """)));

        String result = ollamaClient.complete("Test prompt");

        assertEquals("This is the Ollama response", result);
    }

    @Test
    @DisplayName("complete when HTTP 4xx/5xx should throw AiException")
    void complete_whenHttpError_shouldThrowAiException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        assertThrows(AiException.class, () -> ollamaClient.complete("Test prompt"));
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
                                            "content": "This is the Ollama response"
                                        }
                                    }]
                                }
                                """)));

        assertThrows(AiException.class, () -> ollamaClient.complete("Test prompt"));
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

        String result = ollamaClient.complete("Line 1\nLine 2\tTabbed\"Quoted\\Backslash");
        assertEquals("AI response", result);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("llama3.2")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content")));
    }

    @Test
    @DisplayName("complete should not send Authorization header")
    void complete_shouldNotSendAuthorizationHeader() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"choices": [{"message": {"content": "response"}}]}
                                """)));

        ollamaClient.complete("Test prompt");

        wireMockServer.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withoutHeader("Authorization"));
    }
}
