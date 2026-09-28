# Video Factory

TASK-09 extends the existing generation, assets, storage, cost, prompt, rate-limit and review infrastructure. The additive V11 migration creates video productions, immutable profile/motion versions, remote attempts, local processing runs, operations, frame evidence, loop evidence and bounded collection plans.

```text
Approved image → frozen motion + TASK-03 prompt → async provider attempt
→ immutable raw MP4 → ffprobe/full decode → temporal CV + sampled TASK-04 Vision
→ FFmpeg processed video → loop master → Android/social/preview/poster/thumbnail
→ mandatory human review → READY
```

`VideoGenerationWorker` submits, polls and downloads outside database transactions. Remote IDs are persisted before progression. `VideoPipelineWorker` uses renewable three-minute database leases and delegates expensive work to the dedicated worker. A restart resumes the durable stage; processing may recompute locally, while uncertain paid submission requires reconciliation. `VideoCancellationWorker` requests remote/local cancellation and preserves attempt/cost history. Cancellation cannot guarantee a provider refunds an accepted request.

## Run

```sh
docker compose up --build -d
# NVIDIA optional overlay; keeps the existing image-processing GPU configuration:
docker compose -f compose.yaml -f compose.gpu.yaml up --build -d
```

Dashboard: http://localhost:3000 → Video Factory. Backend health: http://localhost:8080/actuator/health. Worker diagnostics: http://localhost:8003/health. All ports bind to loopback. MinIO and PostgreSQL retain their existing named volumes. Never use `down -v` to update the application.

Choose an approved static image, profile and `mock-video`, leave budget at zero and queue. Watch raw/master/repeated loop playback and evidence before approval. Regeneration creates a new production/generation and may incur AI charges. Reprocessing retains raw media and creates a new run and new variants; it does not call the image-to-video provider again.

## API

Base `/api/v1/video`:

| Operation | Endpoint |
|---|---|
| Start/list/detail | `POST/GET /productions`, `GET /productions/{id}` |
| Version motion plan | `GET/PUT /productions/{id}/motion` |
| Local processing/loop/variants | `POST /productions/{id}/reprocess` |
| New generation | `POST /productions/{id}/regenerate` |
| Human/queue actions | `POST /productions/{id}/{approve,reject,pause,resume,cancel}` |
| Resume a known uncertain remote job | `POST /productions/{id}/reconcile` |
| Immutable profiles | `GET /profiles`, `PUT /profiles/{key}` |
| Diagnostics | `GET /providers`, `/providers/statistics`, `/dashboard`, `/worker-health` |
| Bounded collection production | `PUT /collections/{id}/plan`, `GET /collections/{id}/progress`, `POST /collections/{id}/{pause,resume}` |
| Derived media | `GET /variants/{id}/content` |

Mutations use optimistic revisions. Start, regenerate and reprocess require `Idempotency-Key`; a reused key with different inputs is rejected. Profile edits append a version and do not change running productions. Binary endpoints use checksums as ETags. Spring serves resource range requests for derived MP4 playback.

Collection plans reserve each candidate's complete budget before queueing, cap batch size and total candidates, count human-approved videos toward the target, and pause on exhausted guards. A maximum-attempt plan may pause while its last candidate is still processing. Existing manually created productions in the same collection count conservatively toward the plan. No failed-generation cost is erased.

## Verification

```sh
cd backend
./gradlew test integrationTest bootJar
cd ../frontend
npm ci
npm test
npm run build
cd ..
docker build -t media-factory-video workers/video
docker run --rm media-factory-video python -m pytest -q -p no:cacheprovider
```

On this Windows/OneDrive workspace, `scripts/test-video-backend.ps1 -All` isolates Gradle output in a fresh temporary directory and copies reports back to `backend/build`. Testcontainers requires Docker. Integration tests use fake providers and the local FFmpeg image, never a paid API. See `task-09-report.md` for the executed checks and limitations.
