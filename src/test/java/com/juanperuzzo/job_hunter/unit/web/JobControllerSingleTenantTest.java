package com.juanperuzzo.job_hunter.unit.web;

import com.juanperuzzo.job_hunter.application.port.in.AnalyzeJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.ApproveDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillCompanyWebsitesUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillContactEmailsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.BackfillJobDescriptionsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.CompanyEnrichmentUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchLinkedInAsyncUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchRunStatusUseCase;
import com.juanperuzzo.job_hunter.application.port.in.FetchSourceJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GenerateEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetEmailDraftUseCase;
import com.juanperuzzo.job_hunter.application.port.in.GetJobUseCase;
import com.juanperuzzo.job_hunter.application.port.in.JobWithDraftStatus;
import com.juanperuzzo.job_hunter.application.port.in.ListJobsUseCase;
import com.juanperuzzo.job_hunter.application.port.in.RecordExternalApplyUseCase;
import com.juanperuzzo.job_hunter.application.port.in.SendEmailUseCase;
import com.juanperuzzo.job_hunter.application.port.in.TailorResumeUseCase;
import com.juanperuzzo.job_hunter.domain.model.Job;
import com.juanperuzzo.job_hunter.web.controller.JobController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("JobController single-tenant tests")
class JobControllerSingleTenantTest {

    @Test
    @DisplayName("job listing should use the operator scope without a user id")
    void getAllJobs_whenNoFiltersProvided_shouldListJobsForSingleOperator() throws Exception {
        var listJobsUseCase = mock(ListJobsUseCase.class);
        var job = new Job(7L, "Junior Developer", "Example", "https://example.test/jobs/7",
                "Java role", LocalDate.of(2026, 11, 26), "gupy");
        when(listJobsUseCase.findAllWithDraftStatus(null, null, null, null))
                .thenReturn(List.of(new JobWithDraftStatus(job, null, null, null)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(listJobsUseCase)).build();

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].title").value("Junior Developer"));
    }

    private static JobController controller(ListJobsUseCase listJobsUseCase) {
        return new JobController(
                mock(FetchJobsUseCase.class),
                mock(FetchSourceJobsUseCase.class),
                mock(FetchLinkedInAsyncUseCase.class),
                mock(FetchRunStatusUseCase.class),
                mock(AnalyzeJobUseCase.class),
                mock(GenerateEmailUseCase.class),
                listJobsUseCase,
                mock(GetJobUseCase.class),
                mock(GetEmailDraftUseCase.class),
                mock(ApproveDraftUseCase.class),
                mock(SendEmailUseCase.class),
                mock(TailorResumeUseCase.class),
                mock(CompanyEnrichmentUseCase.class),
                mock(RecordExternalApplyUseCase.class),
                mock(BackfillContactEmailsUseCase.class),
                mock(BackfillCompanyWebsitesUseCase.class),
                mock(BackfillJobDescriptionsUseCase.class));
    }
}
