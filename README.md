# Media Factory

A Java 21 / Spring Boot 4.1.1 and React media production foundation. TASK-02 adds an OpenAI Images adapter, provider routing, shared rate/concurrency controls, attempt history and usage-based cost estimates. Free deterministic mock generation remains the default. Tests never call a paid API.

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

Open **Collections**, create a project, collection, and concept. Select **New generation**, enter a prompt, aspect ratio, quality, and Auto/explicit provider/model. Follow **Generation Queue** and open a generation to inspect its route, attempts, latency, costs and failures. In **Review**, approve, reject, or regenerate. **Assets** retains originals; **Costs** distinguishes known estimates from unknown billing. Regenerate creates a new generation and preserves the prior asset and its review state.

## Mock-only and real-provider modes

Default mock-only mode needs no AI credentials:

```text
MEDIA_FACTORY_IMAGE_PROVIDER=mock
OPENAI_IMAGE_ENABLED=false
MOCK_IMAGE_SCENARIO=success
```

For real generation, set these in your uncommitted `.env` or backend secret environment:

```text
MEDIA_FACTORY_IMAGE_PROVIDER=openai
OPENAI_IMAGE_ENABLED=true
OPENAI_API_KEY=<your own key, set locally>
OPENAI_IMAGE_MODEL=gpt-image-2
```

Then run `docker compose up -d --build`. A generation submitted in this mode may incur provider charges. Keep secrets out of frontend settings and Git. The app never sends the key to the browser. Supported output is one PNG/JPEG at 1024×1024, 1024×1536, or 1536×1024. Unsupported options fail validation. Model access depends on your provider account.

Fallback is disabled by default. `IMAGE_FALLBACK_ENABLED=true` plus `IMAGE_FALLBACK_PROVIDERS=mock` deliberately enables a development fallback. Production additionally requires `MOCK_PRODUCTION_FALLBACK_ENABLED=true` to use mock automatically. Ambiguous paid outcomes require manual acknowledgement by default; review [resilience](docs/resilience.md) before enabling automatic timeout retries.

See [providers](docs/providers.md), [routing](docs/provider-routing.md), [resilience](docs/resilience.md), and [cost tracking](docs/cost-tracking.md). Metrics are available at `http://localhost:8080/actuator/prometheus`.

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

This is a private, single-workspace foundation. It has no user authentication or tenant authorization; do not expose it publicly without an authenticated gateway, TLS, managed secrets, backup/restore procedures, and infrastructure hardening—especially with a paid provider enabled. Compose database/storage defaults are local development defaults. Lists return the newest 200 records. Video orchestration, publishing, metric ingestion, embeddings, and AI-powered upscale remain extension points. Publication and performance schemas are ready for later integration.

MinIO's community repository is archived and old Docker Hub images are unavailable; Compose pins official Quay releases for reproducible local development. For production, use a maintained S3 service through `S3MediaStorage` and review your storage lifecycle and retention requirements. See the [official MinIO repository](https://github.com/minio/minio) and [container documentation](https://min.io/docs/minio/container/index.html).
