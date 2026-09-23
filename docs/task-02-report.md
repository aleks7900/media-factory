# TASK-02 implementation report

## Architecture

`REST API → durable job → replaceable routing strategy → neutral provider interface → OpenAI adapter or mock → immutable media storage → Asset → technical QA`

The router snapshots provider/model choices when accepting a generation. Workers claim jobs using PostgreSQL locking, acquire a shared provider permit, persist an attempt and cost placeholder, then call the adapter outside database transactions. Results record usage and cost before storage. Lease fencing prevents stale workers from committing duplicate assets. Provider DTOs and HTTP handling stay in the adapter package.

## Important files

- `backend/src/main/java/com/mediafactory/provider/openai/`: OpenAI HTTP client, request/response mapping, failure classification and secret configuration.
- `backend/src/main/java/com/mediafactory/provider/routing/`: registry and default/explicit/fallback routing strategy.
- `backend/src/main/java/com/mediafactory/provider/resilience/`: shared quotas, concurrency permits, circuit state and retry decisions.
- `backend/src/main/java/com/mediafactory/provider/`: canonical options, capabilities, configuration, pricing, telemetry and retained mock implementation.
- `backend/src/main/java/com/mediafactory/service/GenerationWorker.java`: durable execution, recovery and fallback.
- `backend/src/main/java/com/mediafactory/service/GenerationAttemptRepository.java`: attempt and cost journal.
- `backend/src/main/java/com/mediafactory/api/ImageGenerationController.java`: asynchronous v1 API and parameter validation.
- `frontend/src/ProviderPanels.tsx`: provider operations, generation controls, attempt timeline, costs and acknowledged recovery.
- `backend/src/test/java/com/mediafactory/RealProviderIntegrationTest.java`, `ProviderPolicyTest.java`, `frontend/src/ProviderPanels.test.tsx`: new regression coverage.
- `.env.example`, `compose.yaml`, `README.md`, `scripts/provider-smoke.ps1` and provider/routing/resilience/cost documentation: configuration and operation.

## Database

Additive `V3__provider_routing_and_attempts.sql` preserves V1/V2. It adds canonical generation request/route/result metadata, selected/final provider, model and timestamps; job routing position, per-provider counters and reconciliation state; `generation_attempts`; shared `provider_runtime`, `provider_permits` and `provider_request_events`. Costs now reference attempts, retain usage/pricing snapshots, distinguish estimated/actual values, and allow unknown usage/cost. All three migrations applied successfully to the existing local database.

## Provider

OpenAI Images uses configurable `gpt-image-2` by default; the initial model allowlist also includes `gpt-image-1.5` and `gpt-image-1`. The adapter supports one text-to-image output, PNG/JPEG, square 1024×1024, portrait 1024×1536, landscape 1536×1024, quality selection and PNG transparency. Unsupported seeds, negative prompts, reference images, batch output and arbitrary dimensions are rejected. Supporting additional API capabilities requires explicit adapter/capability changes.

Mock remains the default and real generation is disabled until enabled with an environment-provided credential. No real provider credential is in the repository, database, frontend or public provider DTO. Safe diagnostics exclude raw provider error bodies and authentication headers.

## Resilience

| Mechanism | Behavior |
|---|---|
| Rate limiting | PostgreSQL rolling-minute admission shared by worker instances; waiting does not count as an AI attempt |
| Concurrency | Shared leased provider permits plus bounded local worker executor |
| Timeout | Configurable connection and whole-response deadlines, cancellation and bounded response body |
| Retry | Configurable finite attempts; permanent failures stop; transient classifications retained per attempt |
| Backoff | Exponential delay with optional jitter; Retry-After is a lower bound; jobs reschedule rather than sleep |
| Circuit | Shared degraded/unavailable state, cooldown and one half-open probe |
| Fallback | Configured ordered route, same generation and preserved attempt history; mock production fallback requires explicit opt-in |
| Ambiguous outcome | Paid timeouts/crashes do not automatically repeat by default; manual retry requires duplicate-spend acknowledgement |

Structured events and Prometheus expose selection, attempts, latency, retries, failures, fallback, storage and estimated cost without high-cardinality generation IDs in metric labels.

## Cost tracking

Every actual provider invocation starts an attempt-linked cost row, including failed calls. Real cost starts unknown. For the configured GPT Image 2 snapshot, estimated USD cost is `(text input tokens × 2.50 + image input tokens × 4.00 + output tokens × 15.00) / 1,000,000`, using decimal arithmetic and eight decimal places. The row stores provider/model/operation, usage details, pricing version `2026-09-24`, currency and outcome. Missing usage or rates remains `UNKNOWN`, never fabricated zero. Real actual cost stays null until reconciliation; mock estimated and actual cost are known zero. Totals group by currency and disclose unknown attempts. See [cost tracking](cost-tracking.md) for source and caveats.

## Verification

10 unit tests, 21 integration tests and 7 frontend tests passed. Testcontainers verifies PostgreSQL/pgvector and MinIO. MockWebServer exercises the actual OpenAI adapter against success, rate limiting, server failures, permanent failures, timeout, concurrent dispatch, fallback and secret-bearing error fixtures. Other checks cover shared quotas/circuit probes, stale paid leases, unknown pricing and minimal HTTP request defaults. Production Java, frontend and Docker builds passed.

Local Compose is running at http://localhost:3000 with all four services healthy. Readiness is `UP`. Both smoke scripts passed, and browser generation reached `QA_PENDING` with an asset preview and successful attempt timeline.

Real provider implementation completed but live generation was not executed because credentials were not available.

## Remaining limitations

- Live account permissions, provider availability and invoice accuracy require credentialed manual verification. Automated tests never spend money.
- OpenAI request correlation is not a promise of provider-side idempotency. Unknown paid outcomes require reconciliation; opting into ambiguous retries can duplicate charges.
- Prices are estimates; cached-token discounts, account-specific rates and invoice reconciliation are not implemented. Other allowed models need configured pricing.
- The application remains private and single-workspace without authentication/tenant authorization. Public deployment needs an authenticated boundary and operational hardening.
- One image per request, no image editing/reference input, and no real video/text/vision/upscale adapters in this task. Lease deadlines bound execution; long-running provider jobs would need another execution model.
- Crash-created orphan objects need a future retention/reconciliation process. Existing list endpoints are capped at 200 records.

## Run

Run `docker compose up -d --build`, then open http://localhost:3000. Defaults require no paid credentials. Use `pwsh -File scripts/smoke.ps1` and `pwsh -File scripts/provider-smoke.ps1` to verify. For a live run, follow [provider configuration](providers.md) and set local environment values from `.env.example`, then recreate the backend. Never commit a populated `.env`.
