# Use Case Interface Refactoring

> Retrospective spec for the Clean Architecture refactorings applied to the
> application port/in interfaces, web controllers, and DTO layer.

## Overview

During the provider-scraping-migration (branch `dev`), several Clean Architecture
improvements were applied to the application and web layers. These refactorings
strengthen layer separation without changing business behavior.

## Changes

### 1. Interface Extraction

Four new inbound port interfaces were extracted to remove direct repository
dependencies from controllers:

| Interface | File | Implemented By | Used By |
|---|---|---|---|
| `UserProfileUseCase` | `application/port/in/UserProfileUseCase.java` | `UserProfileService` | `ProfileController` |
| `ListJobsUseCase` | `application/port/in/ListJobsUseCase.java` | `FetchJobsService` | `JobController` |
| `GetJobUseCase` | `application/port/in/GetJobUseCase.java` | `FetchJobsService` | `JobController` |
| `GetEmailDraftUseCase` | `application/port/in/GetEmailDraftUseCase.java` | `EmailGenerationService` | `JobController` |

**Before**: `JobController` injected `JobRepository`, `JobAnalysisRepository`,
and `EmailDraftRepository` directly and called them in endpoint handlers.

**After**: Controllers depend only on inbound port interfaces.
Repositories are accessed only by service implementations.

**Methods**:
- `UserProfileUseCase.getProfile(Long userId)` → `UserProfile`
- `UserProfileUseCase.saveProfile(Long userId, String resumeText, List<String> skills, CompanyTone tone)` → `UserProfile`
- `ListJobsUseCase.findAll()` → `List<Job>`
- `GetJobUseCase.getById(Long id)` → `Job`
- `GetEmailDraftUseCase.getEmailDraft(Long userId, Long jobId)` → `EmailDraft`

### 2. Use Case Signature Simplification

Two use case interfaces had their parameters changed from domain objects to IDs:

| Interface | Before | After |
|---|---|---|
| `AnalyzeJobUseCase` | `analyze(Long userId, Job job)` | `analyze(Long userId, Long jobId)` |
| `GenerateEmailUseCase` | `generate(Long userId, Job job, JobAnalysis analysis)` | `generate(Long userId, Long jobId)` |

**Reasoning**:
- Controllers should not assemble domain objects before calling use cases
- Use case implementations now fetch entities internally via repository ports
- Interfaces are more decoupled (don't depend on `Job` or `JobAnalysis` domain models)
- Makes use cases callable from any adapter (REST, CLI, test)

**New dependencies** (injected into service classes):
- `AiAnalysisService`: added `JobRepository`
- `EmailGenerationService`: added `JobRepository`, `JobAnalysisRepository`

### 3. DTO Layer Enforcement

`AuthController` now returns `AuthResponse` (web-layer DTO) instead of `AuthResult`
(application-layer record).

| Aspect | Before | After |
|---|---|---|
| Return type | `ResponseEntity<AuthResult>` | `ResponseEntity<AuthResponse>` |
| `AuthResult` usage | Exposed as HTTP response | Internal to application layer |
| Mapping | Direct exposure | `new AuthResponse(result.token(), result.userId(), ...)` |

**Reasoning**: The HTTP response contract should be defined by a web-layer DTO,
not an application-layer record. `AuthResult` remains unchanged — it's the internal
return type of `AuthUseCase`. Only the controller mapping changed.

## Data Flow

### Before (controller assembled entities)
```
Request → Controller (calls JobRepository.findById → Job)
        → analyzeJobUseCase.analyze(userId, job)
```

### After (service assembles entities)
```
Request → Controller
        → analyzeJobUseCase.analyze(userId, jobId)
          → AiAnalysisService (calls JobRepository.findById → Job)
            → proceeds with analysis logic
```

## Test Coverage

| Component | Test File | Type |
|---|---|---|
| AiAnalysisService | `AiAnalysisServiceTest.java` | Unit (Mockito) |
| EmailGenerationService | `EmailGenerationServiceTest.java` | Unit (Mockito) |
| JobController | `JobControllerTest.java` | `@WebMvcTest` |
| ProfileController | `ProfileControllerTest.java` | `@WebMvcTest` |
| AuthController | `AuthServiceTest.java` | Unit |

All existing tests pass with the refactored signatures.