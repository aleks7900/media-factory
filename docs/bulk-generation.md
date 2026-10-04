# Bulk image and video generation

The dashboard has **Bulk GPT Image** and **Bulk Gemini Video** pages and entry cards on the home dashboard. Both use one durable bulk engine. Generation continues when the page closes or the browser refreshes; select the project and batch again to view it.

## Run locally

Use the GPU overlay on this installation:

```powershell
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build
```

Open http://localhost:3000 and select either bulk page. Choose an existing project, name the batch, and select the provider/model/options. Mock image generation runs in Java; mock video generation uses the existing local FFmpeg video worker. Mock video supports 2, 5 or 10 seconds and one reference; its output is a deterministic motion fixture, not AI video.

Production providers require explicit paid-generation acknowledgement before import. Import immediately queues all valid tasks. No paid calls are needed to test the feature.

### Input archive

```text
tasks.zip
├── mountain_car/
│   ├── task.md
│   ├── front.jpg
│   └── side.png
└── city_car/
    └── task.md
```

Each top-level folder is one task. Empty folders and folders missing task.md are invalid tasks. Alternatively use root-level UTF-8 `.md` or `.txt` files, one per task. Do not mix root files with folders. PNG/JPEG references must decode as their actual media type. Prompt text is data and is never executed.

Archive structural/security errors reject the archive. Individual missing prompts, unsupported files, corrupt reference images or incompatible provider options mark that task FAILED; valid siblings queue normally. The post-import summary reports task, invalid-task and reference counts.

The current OpenAI adapter uses `images/generations`, supports exactly one output, and does not accept reference images. Image tasks containing references fail validation explicitly. The UI displays provider-supported formats, qualities and sizes. Reference-edit support requires extending that adapter rather than silently dropping inputs.

Gemini uses Veo through the Gemini API. The implemented models are `veo-3.1-generate-preview` and `veo-3.1-fast-generate-preview`, selecting the configured model. It supports 720p/1080p portrait or landscape output, 4/6/8 seconds, and at most three image references. Reference inputs and 1080p require eight seconds. Wire format and download behavior follow [Google's Veo documentation](https://ai.google.dev/gemini-api/docs/veo). Preview-model availability remains controlled by the provider and account.

## Configuration

Existing OpenAI settings remain authoritative: `OPENAI_IMAGE_ENABLED`, `OPENAI_API_KEY`, `OPENAI_IMAGE_MODEL` and the provider configuration in application.yml. Never put keys into task.md or the browser.

| Variable | Default | Purpose |
| --- | --- | --- |
| `GEMINI_API_KEY` | empty | Server-side Gemini credential; empty disables the provider |
| `GEMINI_VIDEO_MODEL` | `veo-3.1-generate-preview` | Configured model |
| `GEMINI_VIDEO_PRICE_PER_SECOND` | empty | Optional operator-maintained USD estimate; empty remains UNKNOWN |
| `BULK_WORKER_CONCURRENCY` | 3 | Worker threads / bulk provider remote-job ceiling; clamped to 1–16 |
| `BULK_WORKER_REQUESTS_PER_MINUTE` | 20 | Provider admission rate shared through existing durable permits |
| `BULK_WORKER_MAX_ATTEMPTS` | 3 | Maximum automatic generation attempts and failed poll budget |

Bulk provider admission also sees active permits and request history from other pipelines. Existing provider timeouts remain effective. The Gemini adapter uses bounded HTTP responses/downloads, 10-second connect and 60-second read timeouts, a two-minute response-read deadline, and no hidden HTTP retries. Polling normally runs every ten seconds. Each remote reconciliation window is bounded to two hours.

ZIP limits are configurable Spring properties, available as environment variables with dots/dashes converted to underscores:

| Property | Default |
| --- | --- |
| `bulk.zip.max-archive-bytes` | 104857600 (100 MiB) |
| `bulk.zip.max-extracted-bytes` | 268435456 (256 MiB) |
| `bulk.zip.max-entry-bytes` | 16777216 (16 MiB) |
| `bulk.zip.max-files` | 5000 |
| `bulk.zip.max-tasks` | 1000 |
| `bulk.zip.max-depth` | 2 |
| `bulk.zip.max-ratio` | 200 |
| `bulk.zip.max-prompt-bytes` | 40000; service also limits prompts to 10000 characters |
| `bulk.zip.max-image-pixels` | 40000000 |

When increasing upload limits, update Spring multipart and Nginx request limits together. Nginx accepts 102 MiB multipart requests. Results exports are limited to 2 GiB uncompressed; download individual outputs for larger batches. ZIP parsing spools to a generated temporary file and reads bounded entry data; archive paths are never filesystem destinations.

## Architecture and recovery

Migration V16 adds bulk batches, tasks, attempts and audit events. It extends existing provider permits. Each archive task links to a normal collection/concept/generation; completed output is an ordinary immutable asset with SHA-256. No approval or publication is implied. Existing QA/review and single-generation flows remain separate and unchanged.

`BulkGenerationService` owns imports, controls and export manifests. `BulkGenerationWorker` owns claims, attempts, costs and durable state. `GptImageProcessor` delegates through the existing image router; `GeminiVideoProcessor` delegates through the existing video router. Provider URLs, authentication and wire DTOs remain in the Gemini adapter. Arbitrary input references are not falsely recorded as approved video source assets.

Workers claim rows with PostgreSQL locks and five-minute leases. A persisted PREPARED/WAITING_CAPACITY attempt is safe to resume before submission. A known remote ID resumes polling without resubmission. An interrupted synchronous operation first checks its deterministic immutable output key. A non-idempotent request with no output or remote ID becomes `PROVIDER_OUTCOME_UNKNOWN`, blocking retry and regeneration. It is impossible to guarantee exactly-once billing across a remote provider's missing idempotency support; the engine conservatively stops instead of repeating such requests.

Each attempt records provider, model, operation, usage when available, estimated/actual cost and currency in the existing generation-cost ledger. Missing pricing is UNKNOWN, never assumed free. Costs remain attached when a request fails or its outcome is unknown. Every regenerated task gets a new generation with parent lineage; original media is never overwritten.

Temporary rate-limit/timeout/unavailable errors use the shared retry classifier and jittered exponential backoff. Validation/authentication/content-policy errors stop. Poll failures retain the remote ID; exhausted polling becomes an unresolved outcome rather than allowing a new paid submission. Structured logs contain batch/task IDs and event actions, never credentials or raw provider errors.

### Controls

- Pause stops new submissions. Already-submitted work continues polling to preserve paid results.
- Cancel prevents remaining submissions. An in-flight request may finish; its immutable output and cost are retained and its task is marked CANCELLED. Provider-side instant cancellation is not promised.
- Retry runs eligible failed tasks only; validation failures and unknown outcomes are excluded.
- Regenerate creates a new child task. Its idempotency key makes response-loss replay safe.
- Reconcile only resumes an existing remote job or recovers already-stored output. It cannot submit a new request. If neither exists, inspect the provider's request history before creating new work; no blind resubmission override is exposed.
- Delete is a workspace tombstone, preserving original media, costs and audit lineage. It is not a storage-erasure operation.
- Results ZIP contains completed outputs with unique task-ID filenames and a JSON manifest mapping names, IDs, status, metadata and checksums. References and outputs are checksum-verified when read/exported.

## API

All routes below are under `/api/v1/bulk`. The local application has no project ACL: deploy on localhost or behind the existing trusted authenticated gateway requirement.

| Method / route | Behavior |
| --- | --- |
| GET `/capabilities` | Enabled providers, configured models, supported options and archive limits |
| POST `/batches` | Multipart `archive` ZIP + `request` application/json; required `Idempotency-Key` |
| GET `/batches?projectId=…&kind=GPT_IMAGE&status=ALL&search=&page=0` | Project-scoped history, 100 rows/page |
| GET `/batches/{id}` | Counts, status, progress, times and cost summary |
| GET `/batches/{id}/tasks?status=ALL&search=&page=0` | Paged task list |
| POST `/batches/{id}/{pause,resume,cancel,retry-failed,delete}` | Batch controls |
| GET `/tasks/{id}` | Original prompt, references, metadata and attempts |
| POST `/tasks/{id}/{retry,cancel,regenerate,delete,reconcile}` | Task controls; regeneration requires `Idempotency-Key` |
| GET `/tasks/{id}/references/{index}` | Verified reference content |
| GET `/batches/{id}/results.zip` | Streamed completed-results archive |

Output content uses the existing `/api/assets/{assetId}/content` endpoint. Import JSON contains `projectId`, `name`, `kind`, `provider`, `model`, `options` and `authorizePaid`. Reuse the exact same import key and payload after response loss. A conflicting archive/options payload with that key returns 409. Browser controls retain action/import keys for retry in the open page; after a full refresh inspect batch history before reimporting.

## Verification

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test-skills-backend.ps1 -All
# Focused bulk integration tests and JAR:
powershell -ExecutionPolicy Bypass -File scripts/test-bulk-backend.ps1
# Focused parser/provider unit tests:
powershell -ExecutionPolicy Bypass -File scripts/test-bulk-backend.ps1 -Unit
cd frontend
npm test
npm run build
```

The test suites use mocks, a local fake Gemini HTTP server and isolated Testcontainers PostgreSQL. They cover 125-task imports, sibling validation failures, path traversal/bomb limits, idempotency, costs and exports, pause/cancel, immutable regeneration, restart recovery, ambiguous-outcome blocking, bounded provider retries and remote polling without resubmission. Frontend tests cover multipart import, stable operation keys, paid consent, history filtering, detail views and blocked unsafe retries.

Real paid API generation is intentionally not part of acceptance testing. Its account permissions, quotas, billing and generated-media behavior must be validated separately with an explicitly approved paid smoke test.

Local acceptance: run `python scripts/smoke-bulk.py --submit`, wait for the mock queues, then `python scripts/smoke-bulk.py --verify`. This script is fixed to localhost and mock providers. Run `node e2e/bulk-smoke.mjs` from frontend for browser checks. See [verification evidence](bulk-generation-verification.md).
