# TASK-06 implementation and verification

Verified on 25 September 2026. The local image processing pipeline is operational with real, explicitly provisioned Real-ESRGAN weights. Image generation and master visual QA used mock providers; verification incurred no paid AI calls.

## Delivered

- Versioned processing profiles, frozen dependency graphs, shared neural upscale, asynchronous jobs with leases, cancellation, retries, idempotency and reusable immutable artifacts.
- Source/parent/profile/model lineage, SHA-256 checksums, technical validation, attempt manifests and compute usage.
- CPU/CUDA worker with 2× and 4× models, padded tiles, bounded OOM recovery, limits, color conversion, smart crop/manual override, resize, optional filters and JPEG/PNG/WebP encoding.
- Processing dashboard, queue views, profiles, batch submission, crop preview, variants and original/output comparison.
- Eight seeded profiles covering stock, phone/Android wallpaper, social, preview and thumbnail outputs.

The implementation and API are described in [image processing](image-processing.md). Model hashes and licensing are in [upscaling](upscaling.md).

## Verification

| Check | Result |
|---|---|
| Backend unit tests | 72 passed |
| Backend integration tests, including PostgreSQL/pgvector and MinIO Testcontainers | 69 passed |
| Frontend tests | 27 passed |
| Processing worker tests | 14 passed on native CPU and in the GPU container |
| Production builds | Backend bootJar, frontend Vite build and Compose images passed |
| Live browser smoke | Processing page and six output cards rendered without page errors |
| Compose health | PostgreSQL, MinIO, backend, frontend, embedding and processing healthy |
| Original immutability | Downloaded original SHA-256 identical before and after processing |
| Request idempotency | Repeated identical request returned the same run |

The free live pipeline created run `a466078f-c424-4ee3-aab2-b205128bd8c2` from a 1024 × 1024 mock master. It completed all six branches in one attempt. Every downloaded derivative matched its recorded SHA-256 and had VALID technical status:

| Profile | Dimensions | Format |
|---|---|---|
| STOCK_STANDARD | 4096 × 4096 | JPEG |
| WALLPAPER_ANDROID | 1440 × 3200 | JPEG |
| WALLPAPER_PHONE | 1290 × 2796 | JPEG |
| SOCIAL_SQUARE | 1080 × 1080 | JPEG |
| PREVIEW | 1280 × 1280 | WebP |
| THUMBNAIL | 320 × 320 | WebP |

The shared 4× upscale reported CUDA inference of 2,012 ms, total worker operation 5,088 ms, peak allocated CUDA memory 90,948,608 bytes, tiles 256 with margin 16, and zero OOM retries. This is one measured synthetic image, not a throughput guarantee. Wallpaper outputs used the conservative letterbox fallback where the inferred composition could not be safely cropped.

Full machine-readable evidence and downloaded outputs are generated under ignored `storage/data/processing-verification/`; browser evidence is under `frontend/test-results/processing-dashboard.png`.

## Real model benchmarks

Actual NVIDIA GeForce RTX 5080 Laptop GPU execution was verified, with 17,066,033,152 bytes reported VRAM. Separate opt-in benchmarks used one 64 × 96 noise image and included cold model initialization:

| Model | Output | Native CPU | CUDA | Peak CUDA allocation |
|---|---|---|---|---|
| RealESRGAN_x2plus | 128 × 192 | 4,641 ms | 12,423 ms | 92,399,616 bytes |
| realesr-general-x4v3 | 256 × 384 | 3,718 ms | 1,572 ms | 11,383,808 bytes |

These single cold samples are not comparable steady-state performance measurements. CPU peak memory was not measured. GPU OOM handling was exercised through deterministic fault-injection tests; no real GPU OOM was intentionally induced.

## Reproduce from a fresh Docker engine

```powershell
./scripts/provision-processing.ps1
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build --wait
./scripts/processing-smoke.ps1
docker compose exec processing python -m unittest discover -s tests -v
cd frontend
npm ci
npm test
npm run build
node e2e/processing-smoke.mjs
```

Omit the GPU override for CPU-only processing. The first CUDA image is large; allow sufficient disk space. Model provisioning downloads only pinned open-source weights and verifies their checksums. Runtime never downloads processing weights.

Backend verification: build the local MinIO fixture with `docker compose build minio`, then run `backend/gradlew.bat -p backend test integrationTest bootJar`. This Windows checkout required an ASCII temporary source directory and a fresh Gradle cache because existing caches reported missing immutable workspace outputs. Final reports are copied to `backend/build/reports/tests`.

Open http://localhost:3000. Health endpoints: backend `http://localhost:8080/actuator/health`, frontend `http://localhost:3000/health`, processing `http://localhost:8002/health`, embedding `http://localhost:8001/health`.

## Environment and data

After the Docker recovery incident, the previous Docker data disk was unavailable. The user confirmed there was no backup and authorized rebuilding from scratch. The current database and media are fresh; no previous records are claimed to have been recovered. Flyway V1–V8 applied successfully. MinIO/mc are built from pinned official source revisions because the previous registry images were unavailable.

## Remaining limitations

- Real-ESRGAN can invent texture or soften fine detail; no restoration can guarantee factual detail or stock-platform acceptance. Only the two general-purpose models are provisioned.
- Face detection plus spectral saliency is heuristic. Complex scenes, edge subjects and text need human review. Unsafe crop policies can letterbox or fail the branch; human overrides are audited.
- Supported still formats are JPEG, PNG and WebP; no HDR, CMYK, animated media, HEIC or video processing. Untagged input is explicitly treated as sRGB.
- One worker operation runs at a time. Full outputs occupy host memory even with tiled GPU inference. Batch jobs expose individual run progress, without a durable aggregate batch entity.
- Temporary worker workspaces have bounded cleanup. Registered immutable artifacts currently have indefinite retention; automated artifact garbage collection is not implemented. Monitor disk usage and retain source/manifests together.
- Profile publishing and wallpaper-target editing are available through REST; the current profile UI is primarily an inspector.
- Denoise/sharpen stage timings are recorded within IMAGE_PROCESSING usage, rather than separate billable operations. Optional external visual QA is not covered by the live GPU smoke and needs stronger ambiguous-failure accounting before paid production use.
- This remains a private single-workspace deployment. Authentication, distributed GPU scheduling, durable backup automation and production storage lifecycle policies are separate deployment work.

These boundaries mean the verified local workflow is ready to use, while broader production deployment still requires the operational work above.
