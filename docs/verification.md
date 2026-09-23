# Verification report

## TASK-03 — production prompt engine (2026-09-24)

Final checks passed **27 backend unit tests, 35 integration tests and 13 frontend tests**, plus Java 21 bootJar, TypeScript/Vite and Docker production builds. V4 applied to the existing local database without changing earlier migrations. Testcontainers also verified a separate V3-to-V4 upgrade with legacy data. The prompt smoke script and browser workflow verified publication, previews, mock generation, exact persisted snapshots, v2 immutability and A/B attribution. Retry/fallback snapshot guarantees passed integration tests using local fixtures; no paid calls were made. See the [full TASK-03 report](task-03-report.md), [prompt engine](prompt-engine.md) and `scripts/prompt-smoke.ps1`.

The earlier task results below are historical and superseded by these counts.

## TASK-02 — provider routing (2026-09-24)

The final code passed 10 backend unit tests, 21 integration tests (15 real-adapter fixture tests, 5 pipeline tests and 1 MinIO test), and 7 frontend tests. Java 21 bootJar, TypeScript/Vite, and backend/frontend Docker production builds passed. Tests use local HTTP fixtures and Testcontainers; no paid API was contacted.

All four Compose services are healthy. Readiness returned `UP`; Flyway versions 1, 2 and additive version 3 succeeded. Both `scripts/smoke.ps1` and `scripts/provider-smoke.ps1` passed. The latter verifies provider discovery, explicit mock routing, asynchronous v1 creation, idempotency, attempt/cost records, JPEG download, SHA-256, technical QA and Prometheus metrics. Browser verification covered provider health/statistics, generation controls, and a completed mock generation with attempt timeline, cost and original preview.

The deployed smoke test exposed an omitted optional boolean being rejected by Jackson. The request DTO now explicitly defaults it, and an HTTP integration regression test verifies minimal requests and idempotent replay.

Real provider implementation completed but live generation was not executed because credentials were not available.

See [TASK-02 implementation report](task-02-report.md) for architecture, changed files, capabilities, resilience, pricing and remaining limitations. The foundation results below are historical TASK-01 results, superseded by the counts above.

Verified on 2026-09-24 (Europe/Bucharest), Windows with Docker Desktop Linux containers.

| Check | Result |
|---|---|
| Backend unit tests | 5 passed, 0 failures |
| PostgreSQL/pgvector pipeline + HTTP integration tests | 5 passed, 0 failures |
| MinIO immutable S3 integration test | 1 passed, 0 failures |
| Frontend Vitest tests | 4 passed, 0 failures |
| Java 21 production bootJar | Passed |
| TypeScript + Vite production build | Passed |
| Docker frontend/backend production builds | Passed |
| npm dependency audit after Vitest 4.1.11 update | 0 vulnerabilities |

The full API smoke test verifies health, project/collection/concept creation, asynchronous generation, idempotent replay, PNG retrieval from MinIO, technical QA, approval, regeneration lineage, and zero-cost accounting. Browser verification confirmed a real asset grid and rejection removing the reviewed item from the pending grid.

Compose runs PostgreSQL/pgvector, MinIO, backend, and frontend with healthy service checks. The initializer exits successfully after creating the private versioned bucket. Backend readiness includes PostgreSQL and storage availability; liveness is separate. No paid provider was contacted.

## Commands

```text
gradle test integrationTest bootJar --no-daemon
npm test
npm run build
docker compose up -d --build
pwsh -File scripts/smoke.ps1
docker compose ps
```

Backend reports and verified JAR are available under `backend/build/reports/tests/` and `backend/build/libs/`. Docker images build directly from the checked-in project.

## Environment adjustments

- Installed Java 21 and Gradle into ignored `.tools/` for verification; the system Java was version 8.
- On this Windows host, Gradle test workers could not load their classpath under the Cyrillic checkout path. Tests ran from `%TEMP%/media-factory-verification` with `%TEMP%/media-factory-gradle` as cache; reports and JAR were copied back. Container builds succeeded directly from the workspace.
- Windows blocked host port 5432. Compose publishes PostgreSQL on localhost:55432; internal service port remains 5432.
- Official MinIO Quay images replaced unavailable Docker Hub images.
- npm 10 failed resolving optional Vitest peers during an update. npm 11 generated the corrected lockfile; `npm ci` succeeded in the Docker Node image.

## Scope

This verifies the private foundation and image orchestration pipeline, not public multi-tenant operation or paid-provider reliability. All five provider ports have free mock implementations. Video is a fixed valid WebM fixture; upscale is local bicubic resizing. Publishing/metric ingestion and embedding generation remain extension points. Authentication, real provider billing reconciliation, long-running worker heartbeats, and automated orphan cleanup require further implementation before those features are enabled.
