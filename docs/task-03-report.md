# TASK-03 implementation report

## Architecture

Concept → Template → immutable Version → typed Variables → ordered Presets → scoped Experiment resolution → Canonical Prompt → existing Provider Router → Provider Prompt Adapter → immutable Snapshot → existing Generation/Job → Asset/QA.

`PromptEngine` is the sole image prompt construction layer. `FactoryService` accepts canonical prompt requests, preserves old raw APIs as ad-hoc snapshots, and atomically creates route snapshots and the existing durable job. `GenerationWorker` consumes stored adapted prompts. Provider clients remain responsible only for provider transport/execution.

## Domain and database

Additive Flyway **V4__prompt_engine.sql** creates `prompt_templates`, `prompt_versions`, `prompt_variable_definitions`, `prompt_presets`, `prompt_preset_versions`, `prompt_experiments`, `prompt_experiment_variants`, and `rendered_prompt_snapshots`. Generation references version, primary snapshot and experiment/variant; every new attempt references its actual provider snapshot. Existing generation/asset/publication/metric/cost relationships are preserved.

Indexes/constraints include unique template/preset keys, unique per-template/per-preset version numbers, unique variable names per version, unique variant keys per experiment, one snapshot per generation/provider, experiment status, and generation version/experiment/variant indexes. Composite experiment/variant foreign keys prevent mismatched attribution. Database triggers protect published version content/variables, preset revisions, snapshots, running experiment configuration/variants and generation attribution. Existing raw generations are backfilled as LEGACY without invented template attribution. V1–V3 were not edited.

## Rendering and versioning

The dedicated single-pass `{{variable}}` renderer supports no scripting. Validation covers required/default values, strict JSON types, enums, numeric and length bounds, duplicate/unknown names, syntax, presets, and undefined references. Composition order is base → selected presets → pipeline → manual suffixes for both positive and negative prompts. Lightweight deterministic lint emits warnings without LLM calls.

Drafts use optimistic revisions. Publish validates and freezes content/schema; editing published content requires a new numbered draft copy. History and side-by-side section diffs include positive, negative and variable definitions. Preset edits append immutable revisions. Audit timestamps and `local-workspace` actor markers reflect the existing unauthenticated private architecture.

## Provider integration

OpenAI and mock adapters explicitly merge negatives into positive constraints because those image adapters lack native negative fields. The generic adapter strategy also supports native separate negatives. Strategies, warnings and exact strings are stored before provider calls. Preview works with disabled providers and creates no AI operations/costs. Retries use the same snapshot ID; fallback uses a distinct provider adaptation of the same canonical prompt. Regeneration copies its parent's frozen prompt/route/attribution. Remote image determinism is not promised.

## Experiments

Template, collection and pipeline scopes are validated. Stable SHA-256 assignment uses the idempotency key and integer basis-point weights totaling 10,000, supporting 50/50, 80/20 and 3+ variants. Lifecycle is draft/start/pause/resume/complete/cancel with revision checks. Scope/weights/versions/override policy freeze after start. Attribution persists through generation and snapshots, preparing existing assets/publications/metrics for future analytics. No automatic winner is calculated.

## Frontend

The PROMPTS navigation contains Prompt Library, Prompt Editor, Prompt History, Presets and Experiments. Workflows include catalogue creation/archive, positive/negative editing, variable insertion and validation bounds, debounced canonical/provider previews, immutable published views, draft copies, version comparisons, preset revisions, weighted experiments and lifecycle/counts. New generation accepts published prompt versions, typed values, presets, pipeline and visible suffixes. Generation details show stored canonical and provider prompts, variables, preset revisions, constraints and attribution.

## Verification

| Check | Result |
|---|---|
| Backend unit tests | 27 passed |
| Integration tests | 35 passed |
| Frontend tests | 13 passed |
| Java 21 production bootJar | Passed |
| TypeScript/Vite production build | Passed |
| Docker production builds | Passed |
| Existing database upgrade | V4 applied successfully; V1–V3 unchanged |
| Compose services | PostgreSQL, MinIO, backend and frontend healthy |
| Prompt smoke workflow | Passed |

Integration tests run PostgreSQL/pgvector and MinIO via Testcontainers. A dedicated migration test migrates to TASK-02 V3, inserts an old generation, upgrades to V4 and confirms an exact LEGACY snapshot. Prompt tests cover publication/immutability, draft conflicts, concurrent version copies, composition, presets changing between retry attempts, raw requests, HTTP defaults/variable errors, scope/overrides, lifecycle and deterministic attribution. Real-adapter fixture tests verify fallback preserves identical canonical prompts and distinct attempt adaptations; no paid provider is contacted. Distribution tests use fixed deterministic 10,000-key samples for 50/50, 80/20 and 40/30/30 weights.

`scripts/prompt-smoke.ps1` exercised template/variable/negative creation, publication, canonical and OpenAI/mock previews, preset composition, mock generation, stored snapshot/attempt linkage, v2 publication, rejected v1 mutation, unchanged historical snapshots, a running A/B experiment, four attributed generations and idempotent replay. Browser verification created/published **Cinematic Wildlife**, previewed its negative adaptation, generated a portrait through Mock Studio, and inspected the exact stored prompt, preset revision, constraints and successful asset. The Experiments page displayed the smoke experiment's 3/1 allocation across four generations; these counts are not statistical conclusions.

## Remaining limitations

- No user authentication/roles were introduced; audit actors are local-workspace markers. Keep the existing private deployment boundary.
- Snapshot reproducibility preserves inputs, not bit-identical output from remote models. No paid live call was needed or made.
- Prompt lint is heuristic, token counts are rough character-based estimates, and diffs are side-by-side sections rather than word-level analysis.
- Presets are literal fragments, pipeline constraints are registered in code, and the UI shows current preset revisions while the API exposes full history.
- Catalogue lists are bounded at 200. Running experiment resolution is not capped. A future pagination/search design can expand catalogue browsing.
- Experiment configuration is replaced by creating a new experiment; draft reconfiguration and advanced analytics/exposure accounting are future extensions. Regenerations preserve parent attribution and should be separated in later analytics.
- Preview renders do not themselves create audit history or persisted snapshots; accepted generations do. Draft edits retain optimistic revision counts, not every intermediate edit.

## Run

Use `docker compose up -d --build`, then http://localhost:3000. Run `pwsh -File scripts/prompt-smoke.ps1` for the new free workflow. Backend verification uses `./gradlew test integrationTest bootJar` with Java 21 and Docker; frontend uses `npm test` and `npm run build`. On this Windows host Gradle tests ran from the documented ASCII temporary path due to the non-ASCII checkout issue; reports and JAR were copied to `backend/build/`.
