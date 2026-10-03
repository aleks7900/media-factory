# Analytics and asset economics

TASK-10 adds a local, measurement-only Analytics workspace. It does not select winners, predict earnings, change prompts, or call paid AI services. External measurements enter through audited API calls or staged CSV imports; no live platform connector is configured.

## Run

From the repository root:

```powershell
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build
```

Open `http://localhost:3000` and choose **Analytics**. CPU-only installations can use `compose.yaml` alone. Backend health is `http://localhost:8080/actuator/health`; frontend health is `http://localhost:3000/health`.

Flyway V12 extends the original `performance_metrics` ledger and adds mapping, snapshots, imports, exchange rates, explicit cost allocations and durable analytics jobs. V13 converts the daily aggregate into a transactionally rebuilt table supporting asset, collection and date scopes. Existing production media and V1–V11 migrations are not rewritten.

## Read the implementation contract

- [Data model](analytics-data-model.md)
- [Ingestion](analytics-ingestion.md)
- [Economics](asset-economics.md)
- [Cost attribution](analytics-cost-attribution.md)
- [Revenue](analytics-revenue.md)
- [CSV imports](analytics-imports.md)
- [Dashboard and query semantics](analytics-dashboard.md)

## Verification

```powershell
./scripts/test-analytics-backend.ps1 -All
cd frontend
npm test
npm run build
node e2e/analytics-smoke.mjs
node e2e/analytics-api-smoke.mjs
```

The PowerShell runner uses the repository's Java 21 and Gradle installations, an isolated temporary build directory to avoid OneDrive locks, and PostgreSQL Testcontainers. Docker must be running. XML reports are copied to `backend/build/task10-unit-results` and `backend/build/task10-integration-results`.

Run the opt-in synthetic benchmark from the repository root:

```powershell
$env:ANALYTICS_BENCHMARK='true'
./scripts/test-analytics-backend.ps1
Remove-Item Env:ANALYTICS_BENCHMARK
```

The benchmark creates 10,000 assets and 1,000,000 measurement facts inside a disposable PostgreSQL container. It does not load synthetic financial records into the running workspace. Its report is `backend/build/task10-benchmark.json`.

## Access and retention

This follows the existing local workspace authorization model. Compose binds exposed services to loopback. Project filters are reporting filters, not a new multi-tenant authorization boundary. Do not expose this local deployment publicly without the application's authentication/access-control layer.

Financial events, snapshots, rates and finalized allocations are retained. They are not deleted after aggregation. PostgreSQL triggers reject fact mutation; append corrections instead. Database backup/restore remains the deployment operator's responsibility. No telemetry expiration policy silently deletes raw facts.


