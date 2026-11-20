package com.juanperuzzo.job_hunter.unit.infrastructure.scraper.provider;

import com.juanperuzzo.job_hunter.application.port.out.JobDescriptionSourcePort;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.InfoJobsDescriptionSource;
import com.juanperuzzo.job_hunter.infrastructure.scraper.provider.InfoJobsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InfoJobsDescriptionSource")
class InfoJobsDescriptionSourceTest {

    @Mock
    private InfoJobsProvider provider;

    @Test
    @DisplayName("fetchDescription should delegate to the provider and return its result")
    void fetchDescription_whenCalled_shouldDelegateToProvider() {
        var source = new InfoJobsDescriptionSource(provider);
        when(provider.fetchDetailDescription("https://www.infojobs.com.br/vaga-de-x.aspx"))
                .thenReturn(Optional.of("corpo completo"));

        assertEquals(Optional.of("corpo completo"),
                source.fetchDescription("https://www.infojobs.com.br/vaga-de-x.aspx"));

        verify(provider).fetchDetailDescription("https://www.infojobs.com.br/vaga-de-x.aspx");
    }

    @Test
    @DisplayName("fetchDescription should propagate an empty result from the provider")
    void fetchDescription_whenProviderReturnsEmpty_shouldReturnEmpty() {
        var source = new InfoJobsDescriptionSource(provider);
        when(provider.fetchDetailDescription("https://www.infojobs.com.br/vaga-de-y.aspx"))
                .thenReturn(Optional.empty());

        assertEquals(Optional.empty(),
                source.fetchDescription("https://www.infojobs.com.br/vaga-de-y.aspx"));
    }
}
