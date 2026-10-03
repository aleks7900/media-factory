# TASK-11 implementation and verification report

Verified on 2026-10-03, Windows / Docker Desktop, Java 21, PostgreSQL 17 + pgvector. This implements the initial controlled feedback loop. The capability boundaries below are material; it is not an autonomous optimizer or a validated causal inference system.

## Architecture delivered

Flyway V14 adds immutable versioned taxonomy/features/corrections, extraction records, frozen cohort rows, findings and evidence links, hypotheses, registered protocols, append-only results/learnings/audit, durable jobs, saturation results and budget reservations. Existing TASK-03 prompt experiments/assignment and TASK-10 lineage, measurements, costs and FX are reused. Existing image provider jobs, QA, similarity and processing remain the production path.

The extraction service computes eight deterministic pixel features and uses a structured mock Vision extension for semantic observations. Typed values, confidence, source, prompt/model/extractor versions and cost provenance are retained. Human corrections survive re-extraction without replacing the original facts. The mock semantic confidence is deliberately low and excluded by the default analysis threshold.

Historical analysis freezes scope, metric, publication-age window, seed, features and measurement values. It supports scalar/numeric attributes, explicit pairs, requested-versus-observed compliance and production dimensions. Exact population mean/median/percentiles accompany seeded bounded bootstrap uncertainty, effect estimates, sample guards, provider/model/prompt strata and chronological checks. Hypothesis text cannot replace numerical evidence. Findings remain exploratory and surface imbalance, coverage, missing data, selection bias and staleness.

Registered prompt-variable/version trials require separate hypothesis and experiment approval. Published control versions remain unchanged. Plans and variants are immutable; implicit enrollment, regeneration and runtime overrides cannot alter the trial. New generations receive stable TASK-03 assignments. Provider/model drift, unknown charges, admission-budget exhaustion and excessive terminal failures pause work. Interrupted attempt reservations fail closed until reconciled.

Results and learnings preserve insufficient, inconclusive, negative and conflicting evidence. No winner changes production automatically. Cluster saturation uses chronological thirds; diversity/novelty use existing embedding identities and a bounded sample. The UI provides all nine Feedback sections, evidence previews, distributions, plan review, funnel/cost comparisons, learnings, feature corrections and durable-job visibility.

## Verification

- Full backend suite: **122 unit tests + 127 integration tests**, zero failures/errors/skips, with both analytics and feedback benchmark options enabled. XML evidence is under `backend/build/task11-unit-results` and `backend/build/task11-full-results`.
- The feedback integration scenario uses 100 historical images, frozen findings, an approved hypothesis and 40 newly assigned mock images. Those images run through ordinary generation, QA, similarity, thumbnail processing and simulated publication/measurement before result analysis. Tests preserve contrary learnings and reject mutation of evidence. Publication exposure and performance are fixtures, not a claim of real market validation.
- Frontend: **56 tests passed**; TypeScript/Vite production build passed.
- Java production JAR and backend/frontend Docker image builds passed.
- Final targeted feedback regression checks, local migration/health and browser/API smoke checks passed; details below.

## Representative scale result

The disposable benchmark contains **100,000 assets, 1,000,000 visual feature rows and 1,000,000 metric events**. Fixture insertion took **54.137 s** and frozen dataset plus one boolean-dimension/two-group analysis took **17.677 s**. The seeded bootstrap sample is capped at 2,048 observations per group. JVM heap snapshots were 267,853,384 bytes before and 271,355,576 after; these are snapshots, not a peak-memory measurement. See `backend/build/task11-benchmark.json`.

This is a local observation under concurrent test/build load, not a production SLO. Broader dimensions, additional combinations, cold caches and different hardware may take longer. The benchmark does not measure every possible scope or concurrent production workload.

## Explicit boundaries and remaining work

- Semantic Vision and hypothesis Text providers are mock-only. Deterministic image features are real pixel calculations. Video-frame semantic extraction is not implemented; unavailable semantics are warned about.
- Initial ONLINE execution supports ordinary IMAGE prompt-variable/version trials. Wallpaper, stock and video scopes support historical/OFFLINE evaluation; specialized factory-profile online enrollment and provider/profile/threshold intervention adapters are not implemented and are rejected explicitly.
- Processing/publication retain their normal APIs and human gates. Feedback does not automatically drive each specialized factory or publish an experiment. Real downstream exposure/earnings must be imported through TASK-10; missing facts remain unavailable.
- There is no automatic winner/adoption, regression adjustment, formal significance claim, multiple-testing correction, Bayesian stopping rule or externally validated causal conclusion. Confidence never becomes HIGH merely because the mock suggests a hypothesis.
- Saturation is descriptive and recorded separately; it does not automatically create a hypothesis. Exploration/exploitation/saturation intent is explicit; there is no autonomous budget allocation or ranked financial forecast.
- Registered plans cannot be edited; replacement currently uses another reviewed hypothesis/proposal. A dedicated proposal editor/replacement workflow and automatic crash-reservation reconciliation remain future work. Current-page UI text filtering is not a global server-side search.
- Financial admission guards use known quotes and recorded costs, not a guarantee of final external invoices. Downstream services keep their existing cost controls.
- No paid provider calls, external publication, synthetic production earnings import or changes to original asset bytes are required by this verification.

## Running

See `docs/feedback-engine.md` for commands, API routes and configuration. Use the existing GPU Compose overlay on this installation. The pre-migration database backup is `storage/data/feedback-verification/task11-before.sql`; it contains local application data and should remain outside version control.

## Local deployment and final smoke results

Flyway **14 succeeded** on the existing local database after the backup. All seven long-running Compose services are healthy (the MinIO initialization job exited successfully). HTTP checks returned 200 for frontend, backend Actuator, MinIO, embedding, processing and video workers.

The final focused feedback integration run passed all four active scenarios after the provider-drift/reservation and registered-variant immutability assertions were added; its opt-in benchmark was intentionally not repeated. The full-suite benchmark above remains the scale evidence.

Live API smoke passed **11 Feedback routes**, 22 seeded taxonomy definitions and a durable zero-cost feature extraction. Existing Analytics smoke passed **15 routes** and verified **all six original media SHA-256 checksums**. Browser smoke passed all **nine sections** at 1536 px and 1100 px with no page errors or horizontal overflow. Screenshots were inspected; header spacing and the eight-card grid were corrected and rechecked.

Evidence: `frontend/test-results/feedback-api-smoke.json`, `feedback-smoke.json`, `feedback-desktop.png`, `feedback-compact.png`, `analytics-api-smoke.json`; health evidence: `storage/data/feedback-verification/health.json`. The live database contains genuine local data plus a cached mock feature extraction; benchmark assets and simulated performance stayed in disposable Testcontainers.

