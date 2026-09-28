# TASK-09 — Video Factory verification report

Verified locally on 2026-09-28. The free mock image-to-video pipeline is running in Docker Compose and completes through human approval. The real Runway adapter is implemented and fake-server tested, but remains disabled by default.

## Architecture delivered

- Additive V11 schema; existing assets, generations, costs, prompts, reviews, storage and shared provider rate limits are reused.
- Versioned motion plans and profile snapshots; frozen source checksum, prompt, provider request and model provenance.
- Async submission/polling/download attempts with remote IDs, retry/backoff, safe fallback, conservative budget checks, uncertain-submission reconciliation and restart recovery.
- Renewable local processing leases, separate worker admission, cancellation and bounded collection plans.
- Immutable raw video and derived processing versions, checksums, lineage, technical/temporal/semantic evidence and operation records.
- Dashboard, collection controls, provider performance, source/raw/master/loop review, motion editing, timestamped findings, human approval, regeneration and local reprocessing.

## Providers and motion engine

`mock-video` creates an actual MP4 with deterministic FFmpeg motion, zero usage cost, a persisted task ID and asynchronous polling. Its animation is a test fixture, not a semantic AI animation.

Runway uses its documented versioned HTTP API behind the provider-neutral async port. Conservative supported models are `gen4_turbo` and `gen4.5`, 5/10 seconds, portrait/landscape/square dimensions. Unsupported negative prompts and requested generation FPS are rejected. Prompt text carries camera/intensity instructions. Configuration requires explicit enablement, credentials, trusted result hosts and verified operator pricing. Ambiguous requests do not automatically resubmit or fall back.

No live Runway generation was executed. Live paid-provider credentials were not supplied or used for this verification. No paid Vision or video API was called. Runway request/status/error behavior was tested against a local fake HTTP server.

## FFmpeg, loops and QA

FFmpeg/ffprobe 5.1.9 run in the dedicated non-root container. CPU processing supports trim, crop, resize, FPS normalization, optional interpolation, optional stabilization, denoise, sharpening, saturation/color metadata, audio removal and H.264/H.265/AV1 encoding. Complete filter graphs, parameters, encoder/device, wall time and file sizes are recorded. CPU H.264 is the tested live baseline.

DIRECT, CROSSFADE and PING_PONG are implemented and tested. Head/tail frame windows produce visual, luminance, edge, motion-direction and seam metrics. Output loops are revalidated, and a three-repeat preview supports human inspection. Reversal requires explicit eligibility. Alternatives remain available in processing history.

QA includes ffprobe metadata, full decode/integrity, resolution bounds, codec, duration, FPS, bitrate, frame count, size and audio checks. Deterministic temporal analysis covers cuts, flicker, motion intensity, camera shake and frozen-frame timestamps. Five sampled frames use the existing TASK-04 Vision evidence interface with per-frame durable costs. Mock semantic evidence is explicitly marked synthetic; human review is mandatory.

NVENC/QSV encoders were listed, but **all hardware initialization probes returned false on this Docker runtime**. CPU fallback was verified. GPU admission was tested with simulated work at a maximum of one concurrent GPU job; actual GPU encoding/performance was not verified.

## Delivered media families

The live first run generated all 11 expected outputs: processed video, master, Android FHD, Android generic fallback, social vertical, social horizontal, social square, preview, repeated loop preview, poster and thumbnail.

| Output | Default size | FPS | Encoding |
|---|---|---|---|
| Master | 720×1280 | 24 | H.264, CRF 18 |
| Android FHD | 1080×1920 | 30 | H.264, CRF 22 |
| Android generic | 720×1280 | 24 | H.264, CRF 24 |
| Social vertical | 1080×1920 | 30 | H.264, CRF 22 |
| Social horizontal | 1920×1080 | 30 | H.264, CRF 22 |
| Social square | 1080×1080 | 30 | H.264, CRF 22 |
| Preview | 360×640 | 24 | H.264, CRF 28 |
| Poster / thumbnail | fit within 720×1280 / 240×426 | static | JPEG |

QHD is supported as an explicitly requested profile variant but was not produced in the live smoke run. All video outputs use yuv420p and faststart; wallpaper output is silent by default.

## Executed tests and builds

| Check | Result |
|---|---|
| Backend unit tests | **112 passed**, zero failures/errors |
| Backend integration/Testcontainers | **109 passed**, zero failures/errors |
| Video integration subset | 6 tests: full real-FFmpeg mock pipeline, 20-production remote cap, collection guards, restart polling, uncertain submission, failed billed fallback |
| Worker FFmpeg/CV/loop tests | **16 passed** |
| Frontend tests | **43 passed** across 9 files |
| Backend production JAR | Passed |
| TypeScript/Vite production build | Passed |
| Backend/frontend/video Docker builds | Passed |
| V11 migration | Applied successfully; V10 retained |
| Compose health | Backend, frontend, PostgreSQL, MinIO, video, existing processing and embedding healthy |
| Browser smoke | Playback, lazy grid media, approval gate, motion editor and responsive layout passed; zero page errors |

The 20-production test queues 20 candidates and confirms exactly three submitted remote jobs while 17 remain requested; it is an admission/concurrency test, not a benchmark of 20 fully rendered videos. Retry tests cover 429/authentication/unavailability and a lost connection after submission. Recovery resumes the existing remote ID without a second submission. Fallback tests retain both a failed billed attempt and the fallback estimate in total cost. GPU-worker admission is a separate worker test.

Reports are under `backend/build/task09-unit-results`, `backend/build/task09-integration-results`, `backend/build/task09-video-tests.log`; browser screenshots are under `frontend/test-results`. These are local ignored artifacts, while test sources and repeatable scripts are included in the workspace.

## Live verification and performance

Production: `9289fb11-d941-4366-af30-2dae6e4a8592`, final state **READY**.

- Provider: `mock-video`, model `deterministic-motion-v1`.
- Persisted remote task: `mock-29beb781-0876-4b9d-b2d9-02287676d5e9`.
- Raw asset: `f6006f6d-8067-30e2-8e7b-266392485d5c`; 366,165 bytes.
- One provider attempt; total recorded generation/frame-QA cost **0 USD**.
- First processing run: DIRECT, **11,841 ms**, 3,072,779 combined output bytes, loop score **0.96716**.
- Second processing run: CROSSFADE, **2,969 ms**, 840,591 combined output bytes, loop score **0.95645**. It deliberately generated fewer delivery variants, so these times are not an equivalent-work comparison.
- Recorded provider-attempt duration: **13,261 ms**, including orchestration/poll/download time; not isolated AI inference time.
- Raw checksum and first-master checksum remained unchanged after v2. Both masters remain addressable.
- Each first-run output checksum matched its downloaded bytes. MP4 byte-range request returned **HTTP 206**. The browser played the processed master.

Evidence and media downloads: `storage/data/video-verification/report.json`, `worker-health.json` and adjacent MP4/JPEG files. The smoke script explicitly approves only its own newly created free mock video fixture, using an already approved test source. A real production asset still requires human review.

## Remaining limitations

- Runway live credentials/model access, output hosts, current pricing and real generation quality require an operator-run paid smoke test before enabling production use. No such live run is claimed here.
- Paid AI generation is not guaranteed reproducible; local codec/hardware changes may also change encoded bytes.
- Loop scores are heuristic and luminance based; crossfade ghosting and physically implausible reversal require visual review. No perfect-loop guarantee is made.
- Sampled Vision cannot prove temporal identity consistency or detect every short defect. Mock Vision is synthetic; real sampled Vision is opt-in and has not been live-tested here.
- Actual NVENC/QSV encoding could not initialize locally. Multi-worker GPU deployments need coordinated capacity beyond each worker's semaphore.
- FFmpeg optical-flow interpolation is available; advanced learned interpolation and AI-native seamless generation remain future adapters.
- Device-specific Android playback, battery usage, H.265/AV1 support and gapless decoding require testing with the actual client. No external Android backend/player contract was provided; no real Android publication integration was invented.
- Processing durations are elapsed time classified by encoder/device, not CPU/GPU utilization measurements. The short local fixture is not a production-scale throughput benchmark.

Run instructions and detailed contracts: [factory](video-factory.md), [providers](video-providers.md), [motion](video-prompts.md), [processing](video-processing.md), [FFmpeg](ffmpeg.md), [looping](video-looping.md), [QA](video-qa.md), [Android](android-video-wallpapers.md).
