package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.strategy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.juanperuzzo.job_hunter.application.port.out.RawJob;
import com.juanperuzzo.job_hunter.infrastructure.scraper.strategy.RestApiStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(WireMockExtension.class)
@DisplayName("RestApiStrategy query param tests")
class RestApiQueryParamTest {

    private RestApiStrategy strategy;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        strategy = new RestApiStrategy("test", wmRuntimeInfo.getHttpBaseUrl(), 5, "data",
                node -> new RawJob(node.path("name").asText(), "", node.path("url").asText(),
                        null, null, null, null, new HashMap<>()));
    }

    @Test
    @DisplayName("extractWithPath with query params should match WireMock stub")
    void extractWithPath_withQueryParams_shouldMatch() {
        stubFor(get(urlPathEqualTo("/api/jobs"))
                .withQueryParam("jobName", equalTo("test"))
                .willReturn(okJson("""
                    {"data": [{"name": "Dev", "url": "https://a.com/1"}]}
                    """)));

        var jobs = strategy.extractWithPath("/api/jobs?jobName=test");
        assertEquals(1, jobs.size());
        assertEquals("Dev", jobs.get(0).title());
    }
}
