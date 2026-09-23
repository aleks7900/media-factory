# Verification report

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
