# Pipelines

TASK-02 extends this image pipeline with OpenAI, model/capability validation, provider routes, durable attempt history and shared resilience controls. See [resilience](resilience.md) for the current execution order and [provider routing](provider-routing.md) for fallback. The legacy mock-only behavior below remains available as the default route.

The executable foundation pipeline is implemented in `GenerationWorker`. `pipelines/mock-image-v1.yaml` describes it for version control; it is documentation, not a generic YAML interpreter.

1. Create a project → collection → concept.
2. Submit an image generation with a unique idempotency key and a prompt/dimension snapshot.
3. In one transaction create the generation and queued job.
4. Claim the job, increase attempts, attach a lease token and move to GENERATING.
5. Journal a provider-neutral usage/cost estimate and invoke ImageGenerationProvider using the generation ID as operation ID.
6. Update provider/model/operation/input/output/estimated cost/currency and outcome for that attempt; failures retain an auditable estimate.
7. Write a new immutable original to MediaStorage.
8. Under the job lease and checksum locks, validate size, decode integrity, resolution, aspect ratio, and duplicate SHA-256.
9. Save asset and technical review; pass to QA_PENDING or mark REJECTED.
10. Human review approves/rejects. Regenerate creates a new generation linked to the original.

The mock renderer creates real PNGs, deterministically per operation ID, with distinct visual colors across generations. It does not interpret the prompt as a real generative model. Text and vision mock contracts return explanatory mock results. Upscale performs a local 2× bicubic resize. Video returns a generated one-second 128×128 WebM test-pattern fixture; it does not honor requested video dimensions. These ports are available to tests; the executable queue currently orchestrates image generation only.

## Failure behavior

Provider/storage failures retry up to three attempts. Backoff is 10s, 20s, then terminal failure (subsequent manual retries retain cumulative attempts and cap delays at 300s). Each failed attempt retains a reason; a successful completion clears the current failure reason. Manual retry is available only on failed jobs. Worker restarts recover expired leases after five minutes. QA failures are review rejections, not infrastructure retries.

## Extending safely

Add vendor adapters behind the existing ports. Keep vendor DTOs, pricing lookup, credentials, timeouts, usage parsing, and provider error translation inside adapters. Introduce persisted operation accounting for any newly activated text/vision/upscale/video steps and reconcile unknown usage after failures. Real paid providers need tested idempotency, cancellation/timeout handling, lease heartbeats for long tasks, rate limits, and spend controls before activation.

Keep originals immutable. Derived thumbnails/upscales use AssetVariant records and new object keys. New publishing pipelines should commit Publication and the APPROVED → PUBLISHED transition atomically and ingest metrics independently.
