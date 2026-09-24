# TASK-05 completion report

Verified on 2026-09-24 in the existing Windows/Docker Desktop workspace. The complete application is running at **http://localhost:3000**. No paid generation or embedding API was called for this work.

## Architecture delivered

Original asset → SHA-256 → DCT pHash → durable local embedding job → versioned pgvector embedding → bounded database candidate search → contextual profile classification → structured QA findings → human review and publication guard.

The existing SHA check now belongs to the similarity engine. Image/Vision provider routing, immutable prompt snapshots, existing retry/rate-limit infrastructure, storage abstractions, and QA history are preserved. V6 and V7 are additive Flyway migrations; previously applied foundation/provider/prompt/QA migrations were not changed.

Fingerprints, embeddings, pair comparisons, evaluation history, findings, duplicate families/members, human review actions, profiles, jobs, compute accounting, concept embeddings, clustering runs/clusters/members, and staged generation batches are persisted. Original media is never overwritten or deleted; variants are excluded from matching.

## Fingerprints and embeddings

- SHA-256: Java MessageDigest over immutable original bytes; extraction verifies the stored checksum.
- pHash: 32 × 32 bicubic luminance image, DCT-II, low 8 × 8 block, median over 63 AC coefficients, DC bit zero; version `dct32-low8-acmedian-v1`. Hamming distance remains separate from cosine similarity. Low-information images suppress pHash duplicate inference.
- Provider: `local-clip`, Transformers 4.53.3 / PyTorch 2.8.0.
- Model: **`openai/clip-vit-base-patch32`**.
- Revision: **`3d74acf9a28c67741b2f4f2ea7635f0aaf6f0268`**.
- Dimension: **512**, shared image/text projection.
- Preprocessing: RGB, bicubic shortest-edge resize to 224, center crop 224 × 224, divide by 255, means `[0.48145466,0.4578275,0.40821073]`, standard deviations `[0.26862954,0.26130258,0.27577711]`.
- Float32 L2 normalization; finite values, dimension and unit norm validated. Text truncates explicitly at 77 tokens.
- Long-lived worker, bounded batches, CPU verified. AUTO supports CUDA when a compatible runtime/device is supplied; default Docker image uses CPU wheels. GPU performance was not tested.

PostgreSQL **pgvector 0.8.6** was verified live. Embeddings use `vector` plus model-specific partial HNSW expression indexes (`vector(512)`, `vector_cosine_ops`, `m=16`, `ef_construction=100`). pHash uses `bit(64)` / `bit_hamming_ops`. Queries use `<=>` for cosine distance and `<~>` for Hamming distance. Models never share a comparison space. Reindex creates new immutable embeddings; activation requires complete analysis, and rollback preserves old vectors.

## Classification and review

Exact, perceptual, near-duplicate, visually similar, semantically similar, and distinct classifications keep separate SHA/pHash/cosine evidence and collection/concept/prompt-version/lineage context. Embedding similarity alone never establishes a duplicate.

Initial WALLPAPER / STOCK_STRICT / GENERATION_DIVERSITY thresholds are pHash 4/10 and cosine .94/.86; SOCIAL uses 3/8 and .96/.88. Profiles differ in publication and repetition policy. These defaults need production calibration. Existing comparisons retain their initial policy snapshot; publication applies current policy to recorded measurements and human decisions.

Connected duplicate families support explicit canonical selection. Human overrides retain automatic evidence and immutable before/after audit records with reason, actor, time, and revision checks. Decisions are preserved across same-model profile changes. QA receives SIMILARITY findings; completed QA history is not rewritten. Java and database publication safeguards remain effective after human QA approval. Canonical selection permits the selected original through its family's duplicate restriction.

## Collection analysis and budget protection

Deterministic `DBSCAN-cosine-bounded-v1`: epsilon .16, minimum three points, UUID traversal order, at most 200 neighbors per point. Runs persist model, parameters, normalized centroids, representatives, member distances, noise/outliers, and explicit statistics. Statistics include asset/cluster counts, largest-cluster share, mean nearest-neighbor similarity, duplicate-pair count, and outlier count. There is no aggregate diversity score.

Diversity Guard examines the recent 50 frozen prompts and latest collection distribution. Strong repeated-prompt evidence can block strict profiles; semantic similarity alone is advisory. Optional text preflight persists shared-space concept embeddings. Staged generation waits for each chunk's analysis, then pauses remaining dispatch when saturation crosses the profile threshold. CONTINUE, STOP, and CHANGE_PROMPT are audited; previous generation snapshots stay immutable.

## Frontend delivered

- Duplicate Review: image pairs, independent measurements, context, reasoned human decisions, canonical families.
- Similarity Explorer: source original, scope/classification/minimum-cosine filters, candidate grid, local text search.
- Collection Diversity: coverage, scalar statistics, visual families, representatives, outliers, profile selection, paused-batch controls.
- Embedding Jobs: model discovery/activation, backfill/reindex, status, attempts, failures and retry.
- Dashboard: exact/near duplicate counts, pending similarity review, embedding queue/coverage, diversity pauses.

Browser verification covered all four screens with no JavaScript errors. The pair-card layout was visually inspected and corrected. Screenshots are in `frontend/test-results/similarity-*.png`.

## Verification results

| Check | Result |
|---|---|
| Backend unit tests | **66 passed** |
| Backend integration tests | **64 passed**, real pgvector Testcontainers |
| Frontend tests | **24 passed** |
| Python worker contract tests | **4 passed**, fixed projections, no model download/GPU |
| Backend production build | `bootJar` passed; Docker image built |
| Frontend production build | TypeScript / Vite passed; Docker image built |
| Browser smoke | Passed; no browser errors |
| Docker Compose | PostgreSQL, MinIO, backend, frontend, embedding worker healthy |
| Health endpoints | Backend health/readiness, frontend health, embedding health all HTTP 200 |
| Flyway | V6 and V7 applied successfully |
| Real local inference | **23/23 existing originals embedded on CPU**, successful global backfill |
| Real exact/perceptual/near detection | Passed using original, byte copy, JPEG recompression and resize |
| Semantic retrieval | Real CLIP text/image search passed |
| Human override / canonical | Both override directions and canonical selection passed |
| QA integration | Live SIMILARITY findings included exact, perceptual and near matches |
| Publication protection | Live duplicate remained blocked with **HTTP 409 after human QA approval** |
| Retry / failure | Transient local-provider failure retried; invalid/missing analysis did not imply unique |
| Model lifecycle | 100 originals backfilled, reindexed to a second model, activated and rolled back; 200 historical vectors retained |
| Collection clustering | 100-asset fixture completed; separate deterministic two-family/outlier fixture passed |
| Batch pause | Request of 100 in chunks of 10 paused after exactly **40** generations; 60 remained undispatched |
| Variants / API payloads | Derived variants excluded; browser-facing APIs omit embedding/centroid vectors |

Measured real fixture examples: exact copy SHA match, pHash 0, cosine 1.0; perceptual match pHash 2, cosine approximately .9823; near match pHash 8, cosine approximately .9990. Real library comparisons also produced semantically related but visually different candidates (for example pHash 22 with cosine approximately .96), classified as semantic similarity rather than duplicates. First four persisted vector norms ranged from .9999999981 to 1.0000000330.

The latest 100-original **mock embedding backfill plus clustering** fixture took approximately **3.49 seconds** locally. This is a deterministic integration-fixture measurement, not CLIP throughput. In the separate real CPU run, recorded image inference durations ranged from 49 to 528 ms for the observed single/batched requests. These are small-run observations, not a capacity claim. No 100,000-asset or GPU benchmark was performed.

## Run and reproduce

```sh
docker compose up -d --build
# First worker startup downloads the pinned model; later starts reuse the model volume.

cd backend
./gradlew test integrationTest bootJar
cd ../frontend
npm ci
npm test -- --run
npm run build
cd ..
docker compose exec -T embedding python -m unittest discover -s tests -v
```

On Windows use `gradlew.bat` / `npm.cmd`. This workspace's Cyrillic path required running Gradle from an ASCII temporary copy with the bundled Java 21 installation. Final reports and the JAR were copied back to `backend/build/reports`, `backend/build/test-results`, and `backend/build/libs`.

The free live fixture script is `pwsh -File scripts/similarity-smoke.ps1`. It creates a clearly named verification collection and new immutable originals in local MinIO; it does not delete existing data. Then run `cd frontend && node e2e/similarity-smoke.mjs`. The fixture IDs/results are saved in the ignored `storage/data/similarity-verification.json`.

## Remaining limitations

Thresholds need labeled production-image calibration. CLIP can miss fine details, count poorly, and conflate subject meaning with visual identity. HNSW retrieval is approximate; a missing candidate is not proof of global uniqueness. Clustering currently limits a run to 5,000 collection originals and 200 neighbors per point, so very dense neighborhoods approximate exhaustive DBSCAN. The library index itself is not limited to 5,000 assets.

One configured local endpoint serves one revision at a time; model upgrades need a maintenance window or an additional configured provider endpoint for uninterrupted inference. The first model download needs internet access. CPU is verified; GPU deployment requires a compatible CUDA image/runtime and remains unbenchmarked. Local compute monetary cost is unknown rather than fabricated. Authentication remains the existing private-workspace boundary. Full structured prompt/preset changes for paused batches are available through the API; the UI exposes an ad hoc prompt change.

Detailed references: [architecture](similarity-engine.md), [embeddings](image-embeddings.md), [duplicate policy](duplicate-detection.md), [clustering](collection-clustering.md), [Diversity Guard](diversity-guard.md).
