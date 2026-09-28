# TASK-08 — Stock Factory verification report

Implemented and verified on 2026-09-27. Stock Factory reuses the existing generation, Prompt Engine, visual QA, similarity, processing, storage and cost subsystems. It prepares export packages; it does not submit content to a marketplace.

## Architecture

```text
Concept → versioned prompt → generation → visual QA + human review
→ strict similarity → stock JPEG + thumbnail → technical validation
→ final-image observations → versioned metadata → human metadata approval
→ frozen export selection → CSV + images + manifest + validation report
```

The `stock` package owns orchestration, versioned requirements, metadata, ranked keywords, bounded collection plans and immutable exports. V10 adds the associated tables, indexes, constraints, audit events and immutability guards. Originals and TASK-06 processing lineage are referenced rather than copied or overwritten. Leases, heartbeats, revision checks, idempotency and bounded retries protect asynchronous work. Retry resumes the stage that failed; stale workers cannot overwrite cancellation or another worker's lease.

REST APIs and the dark Stock Factory dashboard provide production, collections, visual-review handoff, metadata editing/history, keyword ordering, partial regeneration, explicit warning acknowledgement, bulk review, profile inspection, cost/KPI visibility and export history/downloads. Generic and custom CSV formatting remain behind `StockExportAdapter`.

## Configured profiles and processing

| Profile | Minimum MP | Encoder target | Processing profile | Availability |
|---|---:|---:|---|---|
| STOCK_GENERIC | 4 | 95 | STOCK_MASTER | Enabled |
| STOCK_HIGH_QUALITY | 8 | 98, floor 95 | STOCK_MASTER_HQ | Enabled |
| STOCK_CUSTOM | 4 | 95 | STOCK_MASTER | Enabled |
| STOCK_ADOBE | 4 provisional | 95 | STOCK_MASTER | Disabled pending platform review |

All initial profiles require JPEG, decoded sRGB, no alpha, 1000–8192 pixels per side, at most 64 MP and 50 MiB per file. Orientation is unrestricted. Metadata is English with titles of 5–180 characters, descriptions of 10–1000 and 10–49 unique ranked keywords. See [exact profile configuration](stock-profiles.md).

TASK-06 preserves composition, strips public metadata and selects the smallest required supported neural upscale factor. A 2048×2048 source already exceeds the generic 4 MP threshold and skips upscale. The HQ profile can request 2× from that source. The TASK-08 live fixture verified the generic branch; it does not claim a new live HQ upscale benchmark.

Technical checks cover SHA-256, actual file size/format, dimensions, unrounded MP, orientation, color space, alpha and file integrity. Compression evidence comes from the immutable processing artifact's actual encoder metadata. Missing evidence or below-preference quality produces a warning, never a fabricated measurement.

## Metadata engine

`STOCK_METADATA` template v1 uses Prompt Engine version `00000000-0000-0000-0000-000000000802`, through the `stock-metadata` pipeline. Inputs include the final JPEG's observations, existing QA findings, concept context and frozen stock profile. Prompt resolution preserves version, canonical inputs and experiment assignment provenance.

The free mock Vision provider samples decoded pixels for color/orientation; it does not recognize arbitrary objects. The mock Text provider/model is `mock` / `studio-mock-v1`. It generates abstract fixture titles/descriptions, ordered keywords and internal ABSTRACT category. AI disclosure is explicit and classification defaults to UNDETERMINED. Mock output carries mandatory human-review warnings.

Keywords retain relevance order, normalize Unicode/case/spacing, preserve useful phrases and deduplicate before truncation. Each keyword records rank/source/confidence. Category mapping is profile-driven through `StockCategoryMapper`. Manual edits and approvals append metadata versions; scoped regeneration preserves fields outside its scope. Canonical prompt inputs make caches stable across PostgreSQL JSON field ordering. Cache hits do not invent new operation costs.

## Export

GENERIC_CSV uses comma-separated columns; CUSTOM_CSV uses semicolons. Both use UTF-8, CRLF and the columns `filename,title,description,keywords,category,ai_generated,content_type`. Apache Commons CSV handles escaping and parser round-trips. Spreadsheet formula prefixes are neutralized.

Each ZIP contains `metadata.csv`, `manifest.json`, `validation-report.json` and `images/*.jpg`. Filenames combine an ASCII title slug, production UUID and metadata-version UUID. The manifest records frozen profiles, prompts, processing lineage, metadata and image checksums; CSV/report and package checksums are also retained. Completed packages remain immutable. Editing metadata requires reapproval and a new export.

STRICT blocks a batch with any invalid item; VALID_ONLY reports skipped items. Exact/perceptual/near-duplicate policy is enforced through TASK-05, with additional repeated-source/checksum checks inside a batch. Explicit selection, collection export and incremental export are supported. Initial limits are 500 images and 512 MiB of image data, plus bounded package overhead. See [export behavior and recovery](stock-export.md).

## Tests and builds

| Check | Result |
|---|---|
| Backend unit tests | 99 passed, no failures/skips |
| Backend Testcontainers integration tests | 103 passed, no failures/skips |
| New Stock-specific tests | 16 component + 16 integration tests |
| Frontend tests | 39 passed across 8 files |
| Java production build | `bootJar` passed |
| Frontend production build | TypeScript + Vite passed |
| Backend/frontend Docker images | Built successfully |
| Flyway V10 | Applied successfully |
| Browser smoke | Dashboard, review, keyword editor, exports, profiles and responsive viewport passed; zero runtime errors |

The 50-approved-assets test runs the complete mock-provider pipeline with real decoded 2048×2048 JPEG fixtures. It verifies 50 CSV rows, 50 corresponding images, 53 ZIP entries and every image checksum. Additional tests cover corrupt/undersized/oversized/alpha/color-space failures, Unicode and CSV quoting, metadata cache/history, mandatory human review, invalid export blocking, STRICT/VALID_ONLY, collection/incremental export, exact/near duplicates, bounded budgets, rejected-candidate costs, retry fencing and temporary-file cleanup after an injected package failure. Export v1 remains byte-identical after edits and export v2.

Final backend evidence is in ignored `backend/build/task08-evidence-results/` and `backend/build/task08-evidence.log`. Frontend screenshots are in ignored `frontend/test-results/stock-*.png`.

## Live local verification

The completed live smoke used free mock image/Vision/Text providers, the real local CLIP similarity worker, real TASK-06 JPEG processing and MinIO. The harness explicitly approved its own mock QA fixture and metadata warnings; production human-review rules remain enabled.

Verified fixture: production `0e4a0624-f7b2-4243-9987-20946842d507`, stock variant `4f77810a-45fe-4b71-b627-d3610d9d26a1`, JPEG 2048×2048 (4.194304 MP), 227,667 bytes, sRGB, no alpha, quality-95 encoder evidence. The flow generated metadata, edited a title containing quotes/comma/Unicode, regenerated only keywords, preserved the title, recorded five metadata versions, approved and produced two immutable packages. The original image and export v1 were downloaded again and their checksums matched.

Backend health/readiness returned UP; frontend and MinIO returned HTTP 200; the embedding worker returned UP and the processing worker ONLINE with CUDA available. Flyway versions 9 and 10 were successful. Recorded monetary cost was 0 USD; local compute is not assigned an invented GPU price. A credential-pattern scan of recent backend logs found zero matches. No paid AI requests were made.

The final deployed revision was smoke-tested again after restarting Docker Desktop. Production `fbc1d298-f206-4077-980c-7c90fb658b87` produced stock variant `64ce00bd-e29a-4000-8547-fabab4161748` (2048×2048 JPEG, 208,884 bytes), with actual encoder quality 95, five metadata versions and 0 USD recorded cost. Exports `56621ff6-4fd2-434f-97e6-dfbf64a84a93` and `c70a9452-3091-44a8-8be8-2a413ca9c934` completed; original and first-export checksums remained unchanged. The final browser smoke passed again with zero runtime errors, including the corrected checkbox layout. Current machine-readable evidence is `storage/data/stock-verification/report.json`; the final smoke log is `backend/build/task08-live-final.log`.

The repository's formatting commit `de8f2bb` had changed checksums for already-applied V1–V9 migrations. SQL token comparison against `be2f94b` proved all nine semantically unchanged and their previous checksums matched the database. Migration history was saved before a guarded, transactional checksum-only reconciliation. No schema/data reset was performed. Evidence is in ignored `storage/data/stock-verification/`.

## Run

Follow the repository README for model provisioning and environment prerequisites. Use Java 21 and a running Docker engine.

```powershell
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build --wait
./scripts/stock-smoke.ps1
cd backend
./gradlew.bat test integrationTest bootJar
cd ../frontend
npm test
npm run build
node e2e/stock-smoke.mjs
```

Use base Compose without `compose.gpu.yaml` for CPU processing. Open http://localhost:3000 and select Stock Factory. On this Windows/OneDrive workspace, verification used a fresh temporary Gradle build directory and the ASCII cache path `C:/Users/aleks/.gradle` to avoid locked outputs and Unicode Gradle-worker launch issues.

## Remaining limitations

- Marketplace-specific acceptance rules and upload contracts are not implemented. Adobe stays disabled; exports do not imply submission or acceptance. Future direct upload belongs in an adapter.
- Mock captions and keyword relevance are deterministic workflow fixtures, not production semantic assessments. A real Vision/Text implementation and quality evaluations are needed before using automatic metadata commercially.
- IP-risk flags and lexical checks support human review; they do not prove ownership, release availability or legal clearance. Commercial/editorial classification requires review.
- Versioned localization fields exist, but initial profiles support English only. Concept authoring remains manual; collection plans consume existing concepts within attempt/budget limits.
- ZIP upload/download uses the bounded byte-array storage interface. Larger-scale exports need streaming; orphaned immutable uploads after interruption need an operational retention policy.
- The application retains the existing private local-workspace actor model. Public or multi-user deployment requires authentication/authorization integration.

See [architecture](stock-factory.md), [profiles](stock-profiles.md), [validation](stock-validation.md), [metadata](stock-metadata.md), [keywords](stock-keywords.md), [CSV](stock-csv.md) and [exports](stock-export.md).
