# Image processing

TASK-06 adds approved-master postproduction. Originals remain immutable. No processing result is inserted into `assets`, so TASK-05's original-ingestion trigger does not embed derivatives.

`ProcessingProfile → immutable published version → ProcessingPlanner → frozen DAG → ProcessingExecutor → ProcessingProvider → validated ProcessingArtifact → AssetVariant`.

The backend stores immutable plans at enqueue time, including source checksum, dimensions, engine identity, exact profiles, model/checksum and manual crops. One shared neural upscale node feeds the branches that need more pixels; preview branches that already fit use the original. Branch execution is currently serialized to bound RAM. The DAG supports fan-out and independently persists every branch outcome. Each attempt creates an immutable manifest; retries never rewrite a previous attempt.

The worker normalizes color to sRGB before neural inference (models assume sRGB), then applies crop → Lanczos resize → optional median-blend denoise / unsharp mask → final single-source encoding → validation. The final encoder embeds sRGB ICC. Adaptive JPEG/WebP compression re-encodes the same in-memory pixels using bounded binary search, never a previous lossy output. Stage execution timings appear in both step rows and manifests. Public outputs strip EXIF, XMP and internal generation metadata; DB provenance is retained.

## API

All paths start `/api/v1`:

| Method | Path | Purpose |
|---|---|---|
| POST | `/assets/{id}/process` | `{ "profiles": ["STOCK_STANDARD","PREVIEW"] }`; asynchronous |
| GET | `/processing-runs` | Latest 200 runs and branch progress |
| GET | `/processing-runs/{id}` | Frozen plan, steps, variants, validation, manifests |
| POST | `/processing-runs/{id}/cancel` | Request cancellation at safe boundaries |
| POST | `/processing-runs/{id}/retry` | Re-execute failed branches, reuse valid cached artifacts |
| POST | `/processing-batches` | `{ "assetIds": [...], "profiles": [...] }`, maximum 100 |
| GET | `/assets/{id}/variants` | Dimensions, format, MP, profile version, validation |
| GET | `/variants/{id}/content` | Immutable derivative bytes |
| GET | `/assets/{id}/crop-preview/{profile}` | Detection, automatic rectangle, warnings |
| GET | `/processing-worker` | Worker health/capabilities |
| GET | `/processing-costs` | Local durations/device and optional external QA costs |

`Idempotency-Key` is optional; without it the backend derives a deterministic request identity from the complete frozen plan. Reusing an explicit key with different input is rejected. Sending identical requests does not create another run or final variant. Cache identity includes source ID/checksum, engine, model, parameters and profile version. Source identity prevents incorrect cross-master lineage.

Processing is only accepted for the current QA-approved original and rechecked at execution. A later review change does not overwrite already-produced files. Cancellation prevents incomplete outputs from being registered. Queued cancellation is immediate; active inference checks cancellation between tiles and the backend fences registration after cancellation.

## Operations

PostgreSQL serializes claims using a short transaction-level advisory lock. The worker uses a two-minute renewable lease and a fencing token on artifact registration. Heartbeats run every 15 seconds independently of model execution. Expired jobs are retried within their attempt budget. Network/busy/storage failures retry up to three attempts with increasing delays. Invalid source/profile, unsafe crop, model identity mismatch and exhausted GPU OOM recovery fail explicitly. Successful branches survive partial failure.

`PROCESSING_AUTO_PROFILES=WALLPAPER_ANDROID,PREVIEW` enables a bounded approval scanner (20 originals per scan); blank disables it. Interactive jobs have priority 10; bulk/automatic jobs have priority 0. The current local-workspace API follows the existing application's trust model and should not be exposed publicly without authentication.

Local operations have actual external cost zero and **unknown/null** compute cost and currency. No fabricated USD compute estimate is used. Optional profile `visualQa=true` reuses TASK-04's VisionQualityProvider and QualityPolicyEngine. It defaults to mock; a non-mock `visionProvider` must match the globally enabled QA provider. Evidence, policy and usage are recorded under the processing run, without creating a new master QA review.

Metrics use the `media_factory_processing_*`, `media_factory_upscale_*` and `media_factory_gpu_*` families. Logs contain run/asset/step/artifact IDs and failure codes, never image/base64 payloads.
