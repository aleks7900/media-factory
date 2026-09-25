# TASK-07 verification report

Verified locally on 2026-09-25. The Wallpaper Factory now orchestrates the existing prompt, generation, QA, similarity and processing systems, with immutable publication packages and an explicit human approval gate.

## Delivered

- Durable, revision-checked production stages; pause/resume/cancel, regeneration lineage and reprocessing without overwriting originals.
- Frozen prompt, QA policy and processing-profile versions; standard, premium, AMOLED, lock-screen and home-screen profiles.
- One generated original, one lossless processed wallpaper master, Android device-family derivatives, lightweight previews/thumbnails, checksums and source lineage.
- Deterministic AMOLED analysis of both original and processed master, immutable evidence, configurable thresholds and rejection before publication.
- Collection plans targeting ready wallpapers, bounded batches, attempts/budgets and existing similarity/diversity protection. Rejected generations remain in recorded costs.
- Collection cover selection/processing APIs, collection progress and per-member publication outcomes.
- Publication port, durable local mock, disabled future Android adapter, authentication/asset-transfer interfaces, versioned manifests, external references, retries, idempotency, update/unpublish, dry run and ZIP export.
- Wallpaper dashboard, collections, production queue, wallpaper/AMOLED review, publication queue, published/failure views, metadata editing, bulk actions and existing crop-editor handoff.

## Verification

| Check | Result |
|---|---|
| Backend unit tests | 83 passed |
| Backend Testcontainers integration tests | 87 passed, including 18 wallpaper tests |
| Frontend tests | 33 passed in 7 files |
| Backend production JAR | Built successfully with Java 21 / Gradle 9.1 |
| Frontend production build | TypeScript and Vite passed |
| Docker backend/frontend builds | Passed |
| Compose | PostgreSQL, MinIO, backend, frontend, embedding and CUDA processing healthy |
| Deployed browser | Dashboard, AMOLED grid, QA decision, variants and manifest checked; zero runtime errors |
| Live pipeline | Standard and AMOLED mock productions completed through real local CLIP and CUDA processing, ZIP and mock publication v2 |

Integration coverage includes QA rejection, stale similarity readiness, budget/attempt stops, collection pause, frozen profiles, manual crop history, human approval, metadata invalidation, timeout after remote acceptance, non-retryable failure, partial collection publication, idempotent replay, unpublish/republish and ZIP contents. Processing-provider fixtures in backend contract tests use synthetic outputs; real decoded media was separately exercised by the deployed smoke script. No paid AI calls were made. Large-catalog throughput and real Android delivery were not load-tested.

The local build folder also contains a stale Gradle executor failure XML dated September 24 from an earlier environment failure. The totals above are the current `TEST-com.*.xml` suites from the successful September 25 full run; the current Gradle run completed successfully.

## Observed artifacts

The two smoke productions are `a91f9a7d-b0d1-4941-9215-5d3dab8bd56f` (standard) and `b7c6c76f-0fe6-44c8-b6b6-acfff0ba9d2e` (AMOLED). Both reached PUBLISHED version 2 after publish, replay, unpublish and republish. The script compared every downloaded binary's SHA-256 with stored metadata, checked the unchanged original and verified the exported ZIP checksum.

| Standard output | Actual dimensions | Format | Bytes |
|---|---:|---|---:|
| Master | 4096 × 8192 | PNG | 14,799,804 |
| FHD portrait | 1080 × 2400 | JPEG | 215,654 |
| QHD portrait | 1440 × 3200 | JPEG | 338,003 |
| Generic fallback | 1080 × 1920 | JPEG | 191,065 |
| Preview | 540 × 1080 | WebP | 16,442 |
| Thumbnail | 180 × 360 | WebP | 2,118 |

Preview/thumbnail dimensions preserve aspect ratio inside configured bounds. Master geometry remains preserved rather than being distorted to an exact target. The standard run's cold 4× upscale recorded 14.43 seconds and master derivation 5.16 seconds on this GPU; these are individual observations, not throughput guarantees.

The AMOLED processed master measured black coverage **95.6431%**, near-black **95.6463%**, mean linear luminance **0.03425**, and bright coverage **3.2683%**. It qualified as AMOLED_SUITABLE. This is an explicit synthetic mock fixture, not evidence of photorealistic generation or calibrated human quality.

Ignored local evidence: `storage/data/wallpaper-verification/report.json`, downloaded originals/variants/ZIPs, and `frontend/test-results/wallpaper-grid.png` / `wallpaper-review.png`.

## Boundaries and remaining limitations

Real Android wallpaper backend integration was not implemented because no external backend repository/API contract was provided. The publication boundary, mock implementation, dry-run flow and future backend contract were implemented instead.

- The future adapter intentionally has no invented production endpoint, credential scheme or public storage URL. See [proposed contract](android-wallpaper-backend-contract.md).
- Android family selection uses physical pixels, aspect tolerance and quality preference, with a generic fallback. It excludes masters/covers/previews from normal screen selection. Actual Android client implementation and device testing remain external.
- Launcher safe zones are configurable heuristics. They do not guarantee every clock/widget layout; inspect crops and use manual corrections.
- AMOLED thresholds need calibration against the intended catalog. Pixel analysis cannot establish subject semantics or battery savings; it never darkens media to improve a score.
- The concept library is authored using existing APIs/UI. Automated text-provider concept synthesis is not included; the bounded collection planner consumes that library. Cover ranking currently uses source resolution, not an aesthetic model.
- Exports reject collections over 100 ready wallpapers and cap uncompressed image payload at 256 MiB. Larger catalogs require individual exports or smaller collections; streaming partitioned exports remain future work.
- The application retains its private local-workspace actor model. Authenticated multi-user production deployment, Android asset transfer and public delivery require deployment-specific work.
- Existing generation/QA costs and processing compute usage are exposed separately. Local GPU duration is recorded without inventing a monetary price.

## Reproduction

From the repository root, follow README prerequisites and provision local processing/embedding models as documented there. Keep image and Vision providers on mock for free verification.

```powershell
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build --wait
./scripts/wallpaper-smoke.ps1
cd frontend
npm ci
npm test
npm run build
node e2e/wallpaper-smoke.mjs
```

For CPU-only use base `compose.yaml`. Backend tests/build: from `backend`, run `./gradlew.bat test integrationTest bootJar` with Java 21 and Docker available. Build the local MinIO fixture first on a fresh engine with `docker compose build minio`. See [pipeline](wallpaper-pipeline.md), [collections](wallpaper-collections.md), [variants](android-wallpaper-variants.md), [AMOLED](amoled-pipeline.md) and [publication](wallpaper-publication.md) for API and recovery details.
