# Media Factory

A Java 21 / Spring Boot 4.1.1 and React media production foundation. Free, deterministic mock images exercise the complete generation → storage → technical QA → human review pipeline. No paid AI credentials or calls are used.

## Run the complete system

Prerequisite: Docker Desktop with its Linux engine running and Docker Compose v2.

```sh
docker compose up -d --build
docker compose ps
```

- Dashboard: http://localhost:3000
- Backend health: http://localhost:8080/actuator/health
- Frontend health: http://localhost:3000/health
- MinIO console: http://localhost:9001 (local defaults: `minioadmin` / `minioadmin`)
- PostgreSQL: localhost:55432, database/user/password `mediafactory`

The MinIO initializer creates and versions the private `media-factory` bucket. Flyway installs pgvector and the schema on startup. Named volumes preserve database and object data across `docker compose down`. Copy `.env.example` to `.env` to change local credentials. Ports bind only to localhost.

Open **Collections**, create a project, collection, and concept. Select **New generation**, enter a prompt and dimensions, then follow **Generation Queue**. In **Review**, approve, reject, or regenerate. **Assets** retains originals, and **Costs** shows a zero-cost ledger for mock operations. Regenerate creates a new generation and preserves the prior asset and its review state.

Run the full API smoke test (creates a small demo collection):

```powershell
pwsh -File scripts/smoke.ps1
```

## Local development

Requires JDK 21, Node 22.12+ and Docker. Gradle Wrapper and npm lockfile are included. npm 11 is recommended for dependency updates (npm 10 can fail while resolving optional Vitest peers); `npm ci` uses the checked-in lockfile.

```sh
docker compose up -d postgres minio minio-init
cd backend
./gradlew bootRun
```

In another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Vite proxies `/api` to localhost:8080. Local backend defaults to `LocalMediaStorage` at `storage/data`; Compose uses S3/MinIO. Set `STORAGE_TYPE=s3`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, and `S3_SECRET_KEY` to change this. Set `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` for a separate database. Use `WORKER_ENABLED=false` for an API-only instance.

## Verification

```sh
cd backend
./gradlew test integrationTest bootJar
cd ../frontend
npm ci
npm test
npm run build
```

On Windows use `gradlew.bat`. Integration tests require Docker and run real PostgreSQL + pgvector with Testcontainers; they do not silently skip when Docker is unavailable. Reports are under `backend/build/reports/tests/`. If Gradle's Windows worker fails on a non-ASCII checkout path, use an ASCII checkout/cache path or run the tests in Linux. See [verification](docs/verification.md) for results from this build.

## Repository

| Directory | Purpose |
|---|---|
| backend/ | Domain, transactional services, provider ports, worker, REST, Flyway, tests |
| frontend/ | React + TypeScript + Vite + TanStack Query + Tailwind dashboard |
| workers/ | Worker operation and extraction guide |
| prompts/ | Versioned prompt templates |
| pipelines/ | Versioned declarative pipeline specification |
| storage/ | Local storage policy and development media |
| docs/ | Architecture, domain model, pipeline and verification guides |

## Deployment boundary

This is a private, single-workspace foundation. It has no user authentication or tenant authorization; do not expose it publicly without an authenticated gateway, TLS, managed secrets, backup/restore procedures, and infrastructure hardening. The Compose credentials are development defaults. Lists return the newest 200 records. Video orchestration, publishing, metric ingestion, embeddings, and AI-powered upscale are extension points rather than active pipelines. All five provider ports have free mock implementations. Publication and performance schemas are ready for later integration.

MinIO's community repository is archived and old Docker Hub images are unavailable; Compose pins official Quay releases for reproducible local development. For production, use a maintained S3 service through `S3MediaStorage` and review your storage lifecycle and retention requirements. See the [official MinIO repository](https://github.com/minio/minio) and [container documentation](https://min.io/docs/minio/container/index.html).
