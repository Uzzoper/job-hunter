package com.juanperuzzo.job_hunter.unit.infrastructure.ai;

import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.infrastructure.ai.OpenRouterClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
                "minimax/minimax-m2.5",
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
}
