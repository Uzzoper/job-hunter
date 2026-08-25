package com.juanperuzzo.job_hunter.unit.infrastructure.email;

import com.juanperuzzo.job_hunter.domain.exception.EmailDeliveryException;
import com.juanperuzzo.job_hunter.infrastructure.email.HermesBotEmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

class HermesBotEmailSenderTest {

    private WireMockServer wireMockServer;
    private HermesBotEmailSender hermesBotEmailSender;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(8091);
        wireMockServer.start();

        hermesBotEmailSender = new HermesBotEmailSender(
                "http://localhost:8091",
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
    @DisplayName("send when gateway acknowledges should not throw")
    void send_whenGatewayAcknowledges_shouldNotThrow() {
        stubAcknowledgement();

        assertDoesNotThrow(() -> hermesBotEmailSender.send(
                "juan@example.com", "jobs@company.com", "Application for Junior Dev", "Hello, I am interested..."));
    }

    @Test
    @DisplayName("send should delegate an instruction carrying from, to, subject and body")
    void send_shouldDelegateInstructionWithAllFields() {
        stubAcknowledgement();

        hermesBotEmailSender.send(
                "juan@example.com", "jobs@company.com", "Application for Junior Dev", "Hello, I am interested...");

        wireMockServer.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-hermes-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("default")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("From: juan@example.com")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("To: jobs@company.com")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Subject: Application for Junior Dev")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Hello, I am interested..."))));
    }

    @Test
    @DisplayName("send when HTTP 4xx/5xx should throw EmailDeliveryException")
    void send_whenHttpError_shouldThrowEmailDeliveryException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal Server Error\"}")));

        assertThrows(EmailDeliveryException.class, () -> hermesBotEmailSender.send(
                "juan@example.com", "jobs@company.com", "Subject", "Body"));
    }

    @Test
    @DisplayName("send when timeout should throw EmailDeliveryException")
    @Timeout(value = 10)
    void send_whenTimeout_shouldThrowEmailDeliveryException() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withFixedDelay(10000)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"choices": [{"message": {"content": "EMAIL_SENT"}}]}
                                """)));

        assertThrows(EmailDeliveryException.class, () -> hermesBotEmailSender.send(
                "juan@example.com", "jobs@company.com", "Subject", "Body"));
    }

    @Test
    @DisplayName("send when HTTP 200 carries finish_reason error should throw EmailDeliveryException")
    void send_whenEmbeddedError_shouldThrowEmailDeliveryException() {
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

        assertThrows(EmailDeliveryException.class, () -> hermesBotEmailSender.send(
                "juan@example.com", "jobs@company.com", "Subject", "Body"));
    }

    private void stubAcknowledgement() {
        wireMockServer.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"choices": [{"message": {"content": "EMAIL_SENT"}}]}
                                """)));
    }
}
