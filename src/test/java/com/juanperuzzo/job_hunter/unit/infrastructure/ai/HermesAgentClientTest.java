package com.juanperuzzo.job_hunter.unit.infrastructure.ai;

import com.juanperuzzo.job_hunter.domain.exception.AiException;
import com.juanperuzzo.job_hunter.infrastructure.ai.HermesAgentClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

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
    @DisplayName("complete when HTTP 200 carries finish_reason error should throw AiException")
    void complete_whenEmbeddedError_shouldThrowAiException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{
                                        "message": {
                                            "content": "upstream provider saturated"
                                        },
                                        "finish_reason": "error"
                                    }]
                                }
                                """)));

        var thrown = assertThrows(AiException.class, () -> hermesAgentClient.complete("Test prompt"));
        assertTrue(thrown.getMessage().contains("upstream provider saturated"));
    }

    @Test
    @DisplayName("complete with normal finish_reason should return response text")
    void complete_withNormalFinishReason_shouldReturnResponseText() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{
                                        "message": {
                                            "content": "all good"
                                        },
                                        "finish_reason": "stop"
                                    }]
                                }
                                """)));

        assertEquals("all good", hermesAgentClient.complete("Test prompt"));
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

    @Nested
    @DisplayName("Reasoning models: deliberation must never reach consumers")
    class ReasoningBlockTests {

        @Test
        @DisplayName("complete should strip a leading think block and return only the payload")
        void complete_whenLeadingThinkBlock_shouldStripItAndReturnPayload() {
            stubContent("<think>The candidate has Java experience, so I will score it high.</think>\\n\\nSubject: Candidatura — Desenvolvedor Java");

            var result = hermesAgentClient.complete("Test prompt");

            assertEquals("Subject: Candidatura — Desenvolvedor Java", result,
                    "the reasoning block must never reach a consumer");
        }

        @Test
        @DisplayName("complete should strip a trailing reasoning block and return only the payload")
        void complete_whenTrailingReasoningBlock_shouldStripItAndReturnPayload() {
            stubContent("{\\\"matchScore\\\": 80}\\n\\n<reasoning>Scored 80 after weighing the gaps.</reasoning>");

            var result = hermesAgentClient.complete("Test prompt");

            assertEquals("{\"matchScore\": 80}", result);
        }

        @Test
        @DisplayName("complete should strip every known reasoning tag variant")
        void complete_whenReasoningTagVariants_shouldStripThemAll() {
            for (var tag : List.of("thinking", "thought", "reflection", "scratchpad", "THINK")) {
                stubContent("<" + tag + ">deliberation here</" + tag.toLowerCase(java.util.Locale.ROOT) + ">\\n\\nPAYLOAD");

                assertEquals("PAYLOAD", hermesAgentClient.complete("Test prompt"),
                        "reasoning tag <" + tag + "> must be stripped");
            }
        }

        @Test
        @DisplayName("complete should strip a leading markdown-quoted deliberation block")
        void complete_whenLeadingMarkdownQuote_shouldStripQuoteBlock() {
            stubContent("> **Reasoning**\\n> Weighing the stack requirements first.\\n\\nDear Hiring Manager,");

            var result = hermesAgentClient.complete("Test prompt");

            assertEquals("Dear Hiring Manager,", result);
        }

        @Test
        @DisplayName("complete should keep payload text that merely contains angle brackets or a quote")
        void complete_whenPayloadHasAngleBracketsOrQuotes_shouldKeepThem() {
            stubContent("Experience with Java > 8, Kotlin, and <T> generics\\n> quoted line inside payload");

            var result = hermesAgentClient.complete("Test prompt");

            assertEquals("Experience with Java > 8, Kotlin, and <T> generics\n> quoted line inside payload",
                    result, "a mid-payload quote or angle bracket must survive untouched");
        }

        @Test
        @DisplayName("complete should throw AiException on finish_reason error even when the content carries reasoning")
        void complete_whenEmbeddedErrorWithReasoning_shouldThrowBeforeStripping() {
            wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                        "choices": [{
                                            "message": {
                                                "content": "<think>retrying</think>upstream provider saturated"
                                            },
                                            "finish_reason": "error"
                                        }]
                                    }
                                    """)));

            var thrown = assertThrows(AiException.class, () -> hermesAgentClient.complete("Test prompt"));

            assertTrue(thrown.getMessage().contains("upstream provider saturated"),
                    "the error check must run before any stripping, and report the raw message");
        }

        /** Stubs a 200 response whose single choice content is {@code content} (already JSON-escaped). */
        private void stubContent(String content) {
            wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"choices\": [{\"message\": {\"content\": \"" + content + "\"}}]}")));
        }
    }
}
