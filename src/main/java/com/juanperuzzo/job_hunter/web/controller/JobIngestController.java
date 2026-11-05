package com.juanperuzzo.job_hunter.web.controller;

import com.juanperuzzo.job_hunter.application.port.in.IngestLinkedInPostUseCase;
import com.juanperuzzo.job_hunter.application.port.in.IngestPostCommand;
import com.juanperuzzo.job_hunter.application.port.in.IngestResult;
import com.juanperuzzo.job_hunter.web.dto.IngestRequest;
import com.juanperuzzo.job_hunter.web.dto.JobResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bot-facing ingest of LinkedIn feed posts
 * ({@code docs/specs/linkedin-posts-ingest.md}). Auth is handled ahead of
 * this route by the filter chain ({@code X-Bot-Token} or JWT; 401 otherwise).
 * HTTP mapping: 201 + Job on save, 200 + {@code {ignored: reason}} for each
 * filtered-out variant, 400 on body validation, 502 when the AI gateway
 * fails (existing {@code AiException} mapping).
 */
@RestController
public class JobIngestController {

    private final IngestLinkedInPostUseCase ingestLinkedInPostUseCase;

    public JobIngestController(IngestLinkedInPostUseCase ingestLinkedInPostUseCase) {
        this.ingestLinkedInPostUseCase = ingestLinkedInPostUseCase;
    }

    /**
     * Runs the ingest pipeline and maps its outcome to the HTTP contract of
     * {@code docs/specs/linkedin-posts-ingest.md}:
     * <ul>
     *   <li>{@link IngestResult.Saved} → {@code 201} with the persisted Job</li>
     *   <li>NoContact / Duplicate / NotJob / InvalidExtraction → {@code 200}
     *       with {@code {ignored: reason}} (the filters keep cold posts away
     *       from the AI call)</li>
     *   <li>{@code IllegalArgumentException} from body validation → 400 via
     *       the GlobalExceptionHandler</li>
     *   <li>{@code AiException} from the Hermes gateway → 502 retryable</li>
     * </ul>
     * Authentication is enforced ahead of this route by the filter chain
     * ({@code X-Bot-Token} or JWT; 401 otherwise — see
     * {@code JobIngestControllerSecurityTest}).
     */
    @PostMapping("/api/jobs/ingest")
    public ResponseEntity<Object> ingest(@RequestBody IngestRequest request) {
        var command = toCommand(request);
        return switch (ingestLinkedInPostUseCase.ingest(command)) {
            case IngestResult.Saved saved -> ResponseEntity.status(HttpStatus.CREATED)
                    .body((Object) new JobResponse(
                            saved.job().id(),
                            saved.job().title(),
                            saved.job().company(),
                            saved.job().url(),
                            saved.job().description(),
                            saved.job().postedAt(),
                            saved.job().source(),
                            saved.job().contactEmail(),
                            null,
                            null,
                            null,
                            saved.job().emailSuspect()));
            case IngestResult.NoContact ignored -> ResponseEntity.ok((Object) Map.of("ignored", "no-contact"));
            case IngestResult.Duplicate ignored -> ResponseEntity.ok((Object) Map.of("ignored", "duplicate"));
            case IngestResult.NotJob ignored -> ResponseEntity.ok((Object) Map.of("ignored", "not-a-job"));
            case IngestResult.InvalidExtraction ignored ->
                    ResponseEntity.ok((Object) Map.of("ignored", "invalid-extraction"));
        };
    }

    private static IngestPostCommand toCommand(IngestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("body must be a JSON ingest request");
        }
        return new IngestPostCommand(request.url(), request.text(), request.author(), request.postedAt());
    }
}
