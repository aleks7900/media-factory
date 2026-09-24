# TASK-02 — Real Image Provider + Provider Routing

## Objective

Extend the existing Media Factory foundation with the first production-ready real image-generation integration.

The system must support:

* real image generation through an external AI provider;
* provider-independent domain logic;
* configurable provider routing;
* model selection;
* rate limiting;
* retries with exponential backoff;
* transient vs permanent error classification;
* fallback providers;
* generation timeouts;
* cost tracking;
* provider health monitoring;
* concurrency limits;
* idempotent generation execution;
* complete generation metadata;
* secure API-key handling;
* mock provider preservation for development/testing;
* observability and structured logging.

The architecture must make adding future providers possible without modifying core domain services.

Do NOT tightly couple Media Factory to the first provider.

---

# 1. Existing Architecture

The project already contains the Media Factory foundation created in TASK-01.

Expected concepts include:

* Project
* Collection
* Concept
* Generation
* Asset
* AssetVariant
* QualityReview
* GenerationCost
* Job
* MediaStorage

Expected provider abstraction:

```java
public interface ImageGenerationProvider {

    ImageGenerationResult generate(
        ImageGenerationRequest request
    );
}
```

Before implementing anything:

1. inspect the existing repository;
2. understand the actual TASK-01 implementation;
3. reuse existing abstractions where appropriate;
4. do not duplicate existing entities/services;
5. refactor existing abstractions when necessary instead of creating parallel architecture;
6. preserve existing tests and behavior.

Document significant architectural changes.

---

# 2. Architectural Goal

Image generation must follow this logical flow:

```text
Generation Request
        │
        ▼
Generation Service
        │
        ▼
Provider Router
        │
        ├──── provider selection
        │
        ▼
Rate Limiter
        │
        ▼
ImageGenerationProvider
        │
        ▼
External AI API
        │
        ▼
Generation Result
        │
        ├──── media
        ├──── provider metadata
        ├──── usage
        └──── cost
        │
        ▼
MediaStorage
        │
        ▼
Asset
        │
        ▼
QA_PENDING
```

Failures:

```text
Provider call
    │
    ▼
Error Classifier
    │
    ├── transient
    │      ↓
    │    retry
    │
    └── permanent
           ↓
        fallback?
           │
      ┌────┴────┐
      │         │
     YES        NO
      │         │
      ▼         ▼
 next provider FAILED
```

---

# 3. Provider Abstraction

Review and improve `ImageGenerationProvider`.

The interface must not expose SDK-specific classes.

Example direction:

```java
public interface ImageGenerationProvider {

    String providerId();

    ImageGenerationResult generate(
        ImageGenerationRequest request
    );

    ProviderCapabilities capabilities();
}
```

Provider IDs should be stable internal identifiers.

Examples:

```text
mock
openai
gemini
```

Do not use Java class names as provider IDs.

---

# 4. Provider Capabilities

Different providers support different functionality.

Introduce a capability model.

Example:

```java
public record ProviderCapabilities(
    Set<ImageAspectRatio> supportedAspectRatios,
    Set<ImageFormat> supportedFormats,
    boolean supportsNegativePrompt,
    boolean supportsSeed,
    boolean supportsReferenceImage,
    boolean supportsTransparentBackground
) {}
```

Do not assume every provider supports:

* seed;
* negative prompt;
* arbitrary dimensions;
* image editing;
* reference images;
* transparency;
* the same quality settings.

Provider-specific limitations must be handled explicitly.

Unsupported requested features must not silently disappear.

Either:

1. adapt the request according to an explicitly configured policy; or
2. reject the request with a clear validation error.

---

# 5. Canonical Image Generation Request

Create or refine a provider-neutral request.

Example:

```java
public record ImageGenerationRequest(
    UUID generationId,
    String prompt,
    String negativePrompt,
    ImageAspectRatio aspectRatio,
    Integer width,
    Integer height,
    ImageQuality quality,
    ImageFormat format,
    Long seed,
    Integer numberOfImages,
    Map<String, Object> metadata
) {}
```

Do not put provider SDK objects into this DTO.

Provider adapters are responsible for converting this request into their API format.

---

# 6. First Real Provider

Implement one real production provider.

Use the provider selected/configured for the project.

Structure:

```text
provider/
    image/
        ImageGenerationProvider.java

        mock/
            MockImageGenerationProvider.java

        <real-provider>/
            RealImageGenerationProvider.java
            RealImageProviderClient.java
            RealImageProviderMapper.java
            RealImageProviderProperties.java
            RealImageProviderExceptionMapper.java
```

Separate:

```text
Media Factory domain
        ↓
provider adapter
        ↓
HTTP/SDK client
        ↓
external API
```

Do not spread provider-specific request/response classes through the application.

---

# 7. Configuration

Provider configuration must be externalized.

Example:

```yaml
media-factory:

  image-generation:

    default-provider: provider-a

    routing:
      enabled: true

    providers:

      provider-a:
        enabled: true
        model: ${IMAGE_PROVIDER_A_MODEL}
        api-key: ${IMAGE_PROVIDER_A_API_KEY}

        timeout:
          connect: 10s
          read: 120s

        concurrency:
          max: 3

        retry:
          max-attempts: 4
          initial-delay: 1s
          max-delay: 30s

      mock:
        enabled: true
```

Do not commit secrets.

Update:

```text
.env.example
README.md
```

`.env.example` may contain variable names but never actual credentials.

---

# 8. Secure Secret Handling

API keys must:

* come from environment variables or another configured secret source;
* never be stored in the database;
* never be returned through REST endpoints;
* never appear in logs;
* never appear in exceptions;
* never appear in frontend payloads.

Add tests where practical to ensure secrets are not exposed.

---

# 9. Provider Router

Implement:

```java
ImageProviderRouter
```

The router must choose the provider for each generation.

Initial routing modes:

```text
EXPLICIT
DEFAULT
FALLBACK
```

### EXPLICIT

Generation explicitly requests:

```text
provider = provider-a
```

Use that provider if available.

### DEFAULT

No provider specified.

Use:

```text
media-factory.image-generation.default-provider
```

### FALLBACK

If the selected provider fails with an eligible error, attempt another configured provider.

---

# 10. Routing Policy

Do NOT implement routing as scattered `if/else` logic.

Create a strategy abstraction.

Example:

```java
public interface ImageProviderRoutingStrategy {

    ProviderRoute resolve(
        ImageGenerationContext context
    );
}
```

Possible future strategies:

```text
DefaultProviderRoutingStrategy
CheapestProviderRoutingStrategy
QualityFirstRoutingStrategy
AvailabilityRoutingStrategy
WeightedRoutingStrategy
```

TASK-02 only needs the foundation plus default/fallback behavior.

Do not prematurely implement all future strategies.

---

# 11. Provider Route

A resolved route should be explicit.

Example:

```java
public record ProviderRoute(
    List<String> providers
) {}
```

Example result:

```text
provider-a
provider-b
mock
```

The generation executor attempts them according to the route and fallback rules.

Do not silently fall back to `mock` in production unless explicitly configured.

---

# 12. Fallback Configuration

Make fallback configurable.

Example:

```yaml
routing:

  routes:

    default:
      providers:
        - provider-a
        - provider-b

      fallback:
        enabled: true
```

TASK-02 may initially have only:

```text
real provider
+
mock provider
```

but architecture must support multiple real providers later.

Important:

Mock provider must NOT automatically become production fallback.

Example:

```yaml
mock:
  production-fallback-enabled: false
```

---

# 13. Error Classification

Create a provider-independent error hierarchy.

Example:

```text
ImageGenerationException
│
├── ProviderAuthenticationException
├── ProviderRateLimitException
├── ProviderTimeoutException
├── ProviderUnavailableException
├── ProviderInvalidRequestException
├── ProviderContentPolicyException
└── ProviderUnexpectedException
```

Each provider adapter maps SDK/API errors into these exceptions.

Domain services must never need to understand provider-specific error codes.

---

# 14. Retry Classification

Not every failure should be retried.

Retry candidates:

```text
429 rate limit
timeout
connection reset
temporary provider outage
selected 5xx errors
```

Do NOT retry blindly:

```text
invalid API key
invalid request
unsupported parameter
content-policy rejection
malformed prompt/request
```

Create:

```java
RetryDecisionService
```

or equivalent policy.

The retry policy must be independently testable.

---

# 15. Exponential Backoff

Implement exponential backoff with jitter.

Conceptually:

```text
attempt 1
↓
1 second

attempt 2
↓
2 seconds

attempt 3
↓
4 seconds

attempt 4
↓
8 seconds
```

Add jitter so many workers do not retry simultaneously.

Support configuration:

```yaml
retry:
  max-attempts: 4
  initial-delay: 1s
  multiplier: 2
  max-delay: 30s
  jitter: true
```

Do not implement infinite retries.

---

# 16. Rate Limiting

Implement provider-level rate limiting.

Rate limiting must be independent for each provider.

Support configuration such as:

```yaml
rate-limit:

  requests-per-minute: 20

  concurrent-requests: 3
```

Architecture:

```text
Generation Workers
        │
        ▼
Provider Rate Limiter
        │
        ▼
Provider
```

A burst of 100 generation jobs must not create 100 simultaneous API calls.

---

# 17. Concurrency Limiting

Rate limit and concurrency limit are separate concepts.

Example:

```text
Provider:

requests/minute = 60
max concurrent requests = 3
```

Only three provider requests may execute simultaneously.

Remaining jobs should wait/queue according to the existing job architecture.

Avoid holding database transactions open while waiting for external AI APIs.

---

# 18. Provider Rate-Limit Headers

If the real provider exposes useful rate-limit information in API responses, capture it when practical.

Examples:

```text
remaining requests
reset time
retry-after
```

`Retry-After` must be respected where available.

Do not hardcode assumptions that every provider exposes the same headers.

---

# 19. Timeouts

Every external generation request requires explicit timeouts.

Configure:

```text
connect timeout
request/read timeout
```

A provider request must never wait indefinitely.

Timeout errors should be classified appropriately and may participate in retry/fallback.

---

# 20. Generation Execution

Generation execution should behave approximately like:

```text
load generation
      ↓
validate state
      ↓
resolve provider route
      ↓
select provider
      ↓
check capability compatibility
      ↓
acquire provider permit
      ↓
execute provider call
      ↓
record attempt
      ↓
success?
  │           │
 YES          NO
  │           │
  ▼           ▼
persist     classify
result        error
              │
        retry eligible?
          │        │
         YES       NO
          │        │
        retry   fallback?
                   │
              ┌────┴────┐
             YES        NO
              │          │
        next provider   FAILED
```

---

# 21. Generation Attempts

Create persistent attempt history if it does not already exist.

Recommended entity:

```text
GenerationAttempt
```

Fields:

```text
id

generationId

provider

model

attemptNumber

status

startedAt
completedAt

durationMs

providerRequestId

errorType
errorCode
errorMessage

retryable

fallback

estimatedCost
actualCost
currency
```

Statuses:

```text
STARTED
SUCCEEDED
FAILED
RATE_LIMITED
TIMED_OUT
```

Never lose attempt history when fallback occurs.

---

# 22. Generation State

Preserve a clear generation lifecycle.

Example:

```text
CREATED
QUEUED
GENERATING
GENERATED
QA_PENDING
APPROVED
REJECTED
FAILED
```

A retry should not create a second logical Generation.

Relationship:

```text
Generation
   │
   ├── Attempt 1
   ├── Attempt 2
   └── Attempt 3
```

Fallback also remains within the same logical generation.

---

# 23. Idempotency

Generation execution must be idempotent at Media Factory level.

If the same job is accidentally picked twice:

```text
Worker A
Worker B
```

the system should prevent unnecessary duplicate provider calls whenever reasonably possible.

Use the existing job locking/idempotency architecture from TASK-01.

Do not rely solely on provider-side idempotency unless the provider explicitly guarantees it.

If the provider supports an idempotency key, use:

```text
generationId
```

or a stable derivative as the provider idempotency identifier where appropriate.

---

# 24. External Calls and Transactions

Do NOT do this:

```text
BEGIN DB TRANSACTION

call external image API
wait 60 seconds

COMMIT
```

External network calls must not hold long-running database transactions.

Use short transactional boundaries.

Example:

```text
TX
mark generation GENERATING
create attempt
COMMIT

↓

external provider call

↓

TX
persist result
create asset
update attempt
update generation
COMMIT
```

Handle crashes between these stages safely.

---

# 25. Provider Response

Create provider-neutral result:

```java
public record ImageGenerationResult(
    List<GeneratedImage> images,
    ProviderGenerationMetadata metadata,
    GenerationUsage usage
) {}
```

Example generated image:

```java
public record GeneratedImage(
    byte[] content,
    String mimeType,
    Integer width,
    Integer height,
    String revisedPrompt
) {}
```

Avoid keeping huge binary content longer than necessary.

Prefer streaming/download-to-storage where supported.

---

# 26. Provider Metadata

Persist enough information for reproducibility and debugging.

Store:

```text
provider
model
provider request ID
requested prompt
revised prompt if provider returns one
dimensions
quality
format
seed if applicable
generation timestamp
provider-specific metadata
```

Provider-specific metadata may be stored as JSON.

Do not make core database columns for every possible provider field.

---

# 27. Asset Creation

Successful provider generation must produce immutable original assets.

Flow:

```text
provider result
     ↓
MediaStorage
     ↓
checksum
     ↓
Asset
     ↓
Generation -> GENERATED
     ↓
QA_PENDING
```

Never overwrite an existing original asset.

Calculate:

```text
SHA-256
```

for each generated image.

Reuse existing TASK-01 duplicate detection.

---

# 28. Cost Tracking

Every external AI call must create/update `GenerationCost`.

Track:

```text
generationId
attemptId

provider
model

operation = IMAGE_GENERATION

quantity

inputUnits
outputUnits

estimatedCost
actualCost

currency

pricingVersion
createdAt
```

Do not simply store:

```text
cost = 0.04
```

without context.

---

# 29. Pricing Configuration

Provider pricing changes over time.

Pricing must not be scattered through Java source code.

Introduce configuration or a pricing service.

Example:

```yaml
pricing:

  provider-a:

    image-model-x:

      currency: USD

      standard:
        per-image: 0.04

      high:
        per-image: 0.08
```

The actual configuration must reflect the selected provider's pricing model.

If pricing cannot be determined accurately:

```text
estimatedCost = null
pricingStatus = UNKNOWN
```

Never fabricate a price.

---

# 30. Pricing Version

Store the pricing version/date used for estimation.

Example:

```text
pricingVersion = 2026-09
```

This allows historical cost reports to remain understandable after provider pricing changes.

---

# 31. Failed Attempt Cost

Do not assume failed requests always cost zero.

Architecture must support:

```text
successful attempt → cost

failed attempt → possible cost

fallback attempt → additional cost
```

Total generation cost:

```text
Generation
    ↓
SUM(GenerationAttempt costs)
```

---

# 32. Provider Health

Introduce basic provider health tracking.

States:

```text
HEALTHY
DEGRADED
UNAVAILABLE
DISABLED
```

Signals may include:

```text
recent success rate
timeouts
rate-limit events
5xx failures
manual configuration
```

Do not implement a complex distributed circuit breaker unless justified by the current architecture.

However, design the service so a circuit breaker can be added later.

---

# 33. Circuit Breaker

If the project already uses Resilience4j or equivalent infrastructure, use it appropriately.

Otherwise evaluate whether introducing it improves the implementation.

Desired behavior:

```text
provider repeatedly fails
       ↓
circuit opens
       ↓
temporarily stop sending requests
       ↓
fallback provider
       ↓
later test provider
       ↓
recover
```

Do not duplicate retry logic across multiple incompatible layers.

There must be one understandable resilience strategy.

Document:

```text
rate limiting
retry
timeout
circuit breaker
fallback
```

and their execution order.

---

# 34. Observability

Add structured logs for:

```text
generation started

provider selected

attempt started

attempt succeeded

attempt failed

retry scheduled

fallback triggered

asset stored

generation completed
```

Include safe identifiers:

```text
generationId
attemptId
provider
model
duration
```

Do NOT log:

```text
API keys
authorization headers
binary image data
full provider responses containing sensitive information
```

Avoid logging full prompts at INFO level.

---

# 35. Metrics

Expose metrics compatible with the project's observability stack.

At minimum:

```text
media_factory_image_generation_total

media_factory_image_generation_success_total

media_factory_image_generation_failure_total

media_factory_provider_requests_total

media_factory_provider_rate_limits_total

media_factory_provider_timeouts_total

media_factory_provider_fallback_total

media_factory_provider_request_duration

media_factory_generation_cost
```

Tag carefully.

Useful tags:

```text
provider
model
status
```

Do NOT use:

```text
generationId
prompt
```

as metric labels because they create high cardinality.

---

# 36. REST API

Extend generation API.

Example:

```http
POST /api/v1/generations/images
```

Request:

```json
{
  "conceptId": "...",
  "prompt": "...",
  "aspectRatio": "PORTRAIT",
  "quality": "HIGH",
  "provider": null
}
```

`provider = null` means use routing policy.

Allow explicit provider selection where appropriate.

Response should return generation/job information, not block the HTTP request until a long-running generation finishes.

Example:

```json
{
  "generationId": "...",
  "jobId": "...",
  "status": "QUEUED"
}
```

---

# 37. Generation Details API

Generation details should expose:

```text
status

selected provider

final provider

model

number of attempts

createdAt
startedAt
completedAt

assets

cost

failure information
```

Do not expose secrets or raw internal exceptions.

---

# 38. Provider API

Add read-only provider information endpoint.

Example:

```http
GET /api/v1/providers/image
```

Return safe information such as:

```json
[
  {
    "id": "provider-a",
    "enabled": true,
    "health": "HEALTHY",
    "defaultModel": "model-x",
    "capabilities": {}
  }
]
```

Never return API keys.

---

# 39. Frontend — Providers Page

Extend the existing Providers page.

Display:

```text
IMAGE PROVIDERS

Provider A
Status: HEALTHY
Model: model-x
Requests today: ...
Success rate: ...
Average latency: ...
Cost today: ...

Mock
Status: ENABLED
Environment: development
```

Do not show secret values.

---

# 40. Frontend — Generation UI

Allow user to create an image generation.

Fields:

```text
Concept

Prompt

Aspect ratio

Quality

Provider:
    Auto
    Provider A
    ...
```

Default:

```text
Provider = Auto
```

Display after submission:

```text
QUEUED
↓
GENERATING
↓
QA_PENDING
```

Frontend should poll or use the project's existing async update mechanism.

Do not block the browser request waiting for generation completion.

---

# 41. Frontend — Generation Attempts

Generation details should make fallback/debugging understandable.

Example:

```text
Generation #9281

Provider route

Provider A
   attempt 1
   RATE_LIMITED
   1.2s

Provider A
   attempt 2
   TIMED_OUT
   120s

Provider B
   attempt 3
   SUCCESS
   18.4s

Final cost
$0.08
```

This information is important for operating the factory.

---

# 42. Development Mode

The existing Mock provider must remain available.

Support:

```text
MEDIA_FACTORY_IMAGE_PROVIDER=mock
```

Developers must be able to run the entire application without real API credentials.

Mock provider should simulate:

```text
success
rate limit
timeout
provider error
```

This is necessary for resilience tests.

---

# 43. Integration Tests

Add comprehensive integration tests.

Use MockWebServer, WireMock, or the existing project's equivalent.

Do NOT call the real paid API from automated tests.

Cover at minimum:

### Success

```text
generation
→ provider
→ image
→ storage
→ Asset
→ QA_PENDING
```

### Retry

```text
provider returns transient error
→ retry
→ success
```

### Rate limit

```text
429
→ Retry-After
→ retry
→ success
```

### Permanent error

```text
400
→ no retry
→ FAILED
```

### Authentication

```text
401
→ no retry storm
→ failure
```

### Timeout

```text
provider timeout
→ retry according to policy
```

### Fallback

```text
primary provider fails
→ secondary provider selected
→ success
```

### All providers fail

```text
primary fails
secondary fails
→ Generation FAILED
```

### Idempotency

```text
same generation job executed twice
→ no unnecessary duplicate generation
```

### Cost

```text
generation
→ cost record persisted
```

### Secrets

Ensure API credentials are not exposed through REST responses.

---

# 44. Concurrency Tests

Add tests demonstrating provider concurrency limits.

Example:

```text
10 jobs
max concurrency = 2
```

Verify no more than two provider calls execute concurrently.

Tests must remain deterministic.

---

# 45. Failure Recovery

Consider application crash scenarios.

Example:

```text
Generation = GENERATING

application crashes

provider response unknown
```

Implement/document recovery behavior.

At minimum, stale generations must be detectable.

Possible strategy:

```text
GENERATING
+
startedAt older than threshold
→ recovery process
```

Do not blindly create another paid generation without considering duplicate-generation risk.

Document limitations when a provider offers no idempotency or status lookup API.

---

# 46. Database Migration

Create Flyway migrations for all new persistence structures.

Potential additions:

```text
generation_attempt

provider metadata

routing metadata

pricing metadata

cost enhancements
```

Follow existing naming conventions.

Do not modify already-applied migrations.

---

# 47. Documentation

Create/update:

```text
docs/providers.md
docs/provider-routing.md
docs/resilience.md
docs/cost-tracking.md
```

`providers.md`:

```text
how providers work
how to add a provider
configuration
capabilities
```

`provider-routing.md`:

```text
routing algorithm
explicit provider
default provider
fallback
```

`resilience.md`:

```text
timeouts
rate limiting
concurrency
retry
backoff
circuit breaker
fallback
```

Include a diagram showing execution order.

`cost-tracking.md`:

```text
pricing source
cost estimation
attempt costs
generation total cost
pricing versions
unknown pricing
```

---

# 48. README

Add setup instructions.

Example:

```text
IMAGE_PROVIDER_API_KEY=...
IMAGE_PROVIDER_MODEL=...
```

Explain how to run:

```text
mock-only mode
```

and:

```text
real-provider mode
```

Do not put actual credentials into documentation.

---

# 49. Definition of Done

TASK-02 is complete only when all of the following work.

### Real generation

A user can:

```text
create Concept
↓
request image generation
↓
job queued
↓
real provider called
↓
image stored
↓
Asset created
↓
Generation becomes QA_PENDING
```

### Resilience

System correctly handles:

```text
429
timeouts
temporary provider failures
permanent errors
fallback
```

without uncontrolled retry loops.

### Cost

Every provider attempt has enough information to calculate or represent its cost.

### Security

No provider secret is exposed through:

```text
logs
database
REST API
frontend
Git
```

### Development

Application remains fully usable with the Mock provider and no paid API credentials.

---

# 50. Verification

Before completing the task:

1. run backend unit tests;
2. run backend integration tests;
3. run frontend tests;
4. run production backend build;
5. run production frontend build;
6. start required Docker Compose services;
7. verify database migrations;
8. verify Mock provider generation;
9. verify one real-provider generation manually if credentials are configured;
10. verify generated media is stored correctly;
11. verify `Asset` and `GenerationAttempt` records;
12. verify cost tracking;
13. simulate 429 and verify retry;
14. simulate timeout and verify retry;
15. simulate primary-provider failure and verify fallback;
16. verify concurrency limit;
17. verify no API key appears in logs or API responses.

---

# 51. Final Codex Report

When implementation is complete, provide a concise implementation report containing:

## Architecture

Explain the final generation path:

```text
Generation
→ Router
→ Resilience Layer
→ Provider
→ Storage
→ Asset
→ QA
```

## Files Changed

List important files created or modified.

## Database

List migrations and schema changes.

## Provider

Document:

```text
provider
model
supported capabilities
known limitations
```

## Resilience

Report implemented behavior for:

```text
rate limiting
concurrency
timeout
retry
backoff
circuit breaker
fallback
```

## Cost Tracking

Explain exactly how costs are calculated and persisted.

## Tests

Report:

```text
unit tests
integration tests
frontend tests
build status
```

## Manual Verification

Report whether a real generation was tested.

If credentials were unavailable, explicitly state:

```text
Real provider implementation completed but live generation
was not executed because credentials were not available.
```

Never claim that a real provider call was verified unless it actually occurred.

## Remaining Limitations

List any technical limitations or follow-up work discovered during implementation.

---

# Engineering Principles

Throughout this task follow these rules:

1. Keep domain logic provider-independent.
2. Treat external AI APIs as unreliable dependencies.
3. Never hold database transactions during long external calls.
4. Never retry permanent failures.
5. Never use infinite retries.
6. Preserve every generation attempt.
7. Track costs per attempt, not only per successful generation.
8. Never silently downgrade unsupported generation parameters.
9. Never expose provider credentials.
10. Never silently use Mock as production fallback.
11. Preserve immutable original generated assets.
12. Prefer idempotent operations.
13. Make failure states observable.
14. Keep provider routing replaceable.
15. Do not over-engineer future routing strategies before they are needed.
16. Use existing project conventions instead of creating duplicate infrastructure.
17. All production behavior must be configurable.
18. Tests must not consume paid AI API resources.

The result of TASK-02 should turn Media Factory from a mock pipeline into a production-capable image-generation platform while keeping the architecture ready for multiple competing image providers.
