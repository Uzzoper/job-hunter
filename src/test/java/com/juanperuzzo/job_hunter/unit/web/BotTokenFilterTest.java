package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.infrastructure.security.BotTokenFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("BotTokenFilter tests (self-hosted single token)")
class BotTokenFilterTest {

    private static final String TOKEN = "s3cr3t-bot-key";

    @Test
    @DisplayName("constructor with blank api-key should throw IllegalStateException (fail-fast)")
    void constructor_whenApiKeyBlank_shouldThrow() {
        assertThatThrownBy(() -> new BotTokenFilter("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot.service.api-key must be set");
        assertThatThrownBy(() -> new BotTokenFilter(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("doFilter with valid X-Bot-Token should pass through")
    void doFilter_whenValidToken_shouldPassThrough() throws Exception {
        var filter = new BotTokenFilter(TOKEN);
        var request = new MockHttpServletRequest("GET", "/api/jobs");
        request.addHeader("X-Bot-Token", TOKEN);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter with missing X-Bot-Token should answer 401 without continuing the chain")
    void doFilter_whenMissingToken_shouldAnswerUnauthorized() throws Exception {
        var filter = new BotTokenFilter(TOKEN);
        var request = new MockHttpServletRequest("GET", "/api/jobs");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("unauthorized");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter with wrong X-Bot-Token should answer 401 without continuing the chain")
    void doFilter_whenWrongToken_shouldAnswerUnauthorized() throws Exception {
        var filter = new BotTokenFilter(TOKEN);
        var request = new MockHttpServletRequest("GET", "/api/jobs");
        request.addHeader("X-Bot-Token", "wrong-key");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter should also protect actuator and health paths")
    void doFilter_whenActuatorPathWithoutToken_shouldAnswerUnauthorized() throws Exception {
        var filter = new BotTokenFilter(TOKEN);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
