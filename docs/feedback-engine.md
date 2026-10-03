# Feedback and experimentation engine

TASK-11 adds an explicit, human-reviewed learning loop: TASK-10 measurements → versioned visual features → frozen dataset → exploratory findings → narrative hypotheses → registered TASK-03 experiment → ordinary production pipeline → measured result → learning. None of these steps rewrites production prompts automatically.

## Run

```powershell
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build --wait
```

Open <http://localhost:3000> and select **Feedback**. CPU deployments can omit the GPU overlay. PostgreSQL applies Flyway V14 automatically. The existing schema, analytics ledgers, originals and embeddings are retained. Back up PostgreSQL before upgrading.

Start with a collection containing historical assets and explicit TASK-10 event imports. Select **Extract collection features**, then **Analyze patterns**. Market comparisons need published assets with a complete observation window and available metrics. No evidence is preferable to invented zero measurements. Review a finding, generate and approve a hypothesis, register the control/treatment plan, approve and start the experiment, then queue generation batches. QA, similarity, processing and publication retain their existing review gates. Ingest subsequent platform measurements through TASK-10 and run experiment analysis after sufficient exposure.

## API and jobs

All feedback routes are below `/api/v1/feedback`. Reads: `overview`, `attributes`, `findings`, `hypotheses`, `analyses`, `experiments`, `results`, `learnings`, `saturation`, `data-quality`, `jobs`. Lists have 50-row pages unless documented as bounded diagnostics. Individual findings include the analysis parameters, statistics and representative assets. Individual experiments include immutable plans, versions, funnel, cost evidence and results.

`POST /analyses` or `POST /jobs/{TYPE}` requires `Idempotency-Key`. Types: FEATURE_EXTRACTION, REEXTRACT_VISUAL_FEATURES, DATASET_BUILD, PATTERN_ANALYSIS, SATURATION_ANALYSIS, HYPOTHESIS_GENERATION, EXPERIMENT_GENERATION, EXPERIMENT_ANALYSIS. Payload conflicts under an existing key are rejected. Extraction accepts assetId or collectionId, extractorVersion and optional UTC from/to. Collection work chains batches of 25. Generation enqueues at most 25 per request. Jobs persist attempts, leases, safe failure reasons, timestamps and results; recover expired leases and retry up to three times. Work and successful job completion commit together, with a lease token and locked job row. Mock-only semantic/text calls permit transactional safe replay without repeated paid calls.

The default worker polls every five seconds. `media.worker.enabled=false` disables automatic workers in integration tests. Expensive analyses and provider operations are queued, not run from page loads. SQL snapshots and aggregation keep the complete population outside JVM memory. Bootstrap samples are bounded separately from exact population statistics.

## Configuration

Spring properties: `feedback.minimum-sample-size` (20), `feedback.minimum-observation-days` (7), `feedback.finding.stale-after-days` (90), `feedback.poll-delay-ms` (5000), `feedback.stale-check-ms` (3600000), `feedback.safety.minimum-resolved` (10), `feedback.safety.maximum-failure-ratio` (0.5). Hypothesis intent is explicit EXPLORATION, EXPLOITATION or SATURATION; there is no automatic budget allocation or approval.

## Verification

```powershell
$env:FEEDBACK_BENCHMARK='true'
./scripts/test-feedback-backend.ps1 -All
cd frontend
npm test -- --run
npm run build
node e2e/feedback-smoke.mjs
```

The backend runner uses an isolated temporary Gradle output directory to avoid OneDrive locks and copies test results into `backend/build/task11-*-results`. Tests use disposable PostgreSQL/pgvector Testcontainers and free adapters. Never import synthetic benchmark earnings into production.

See [visual attributes](visual-attributes.md), [analysis](feedback-analysis.md), [statistics](feedback-statistics.md), [hypotheses](hypothesis-engine.md), [proposals](experiment-proposals.md), [results](experiment-analysis.md), [learnings](learnings.md), [saturation](saturation-analysis.md) and the [verification report](task-11-report.md).
