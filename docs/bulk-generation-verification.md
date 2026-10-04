# Bulk generation verification — 2026-10-04

## Automated checks

| Check | Result |
| --- | --- |
| Full Java unit suite | 135 passed |
| Full Testcontainers integration suite | 141 passed, 2 intentionally skipped opt-in scale benchmarks |
| Final focused bulk integration suite | 7 passed after the final lock-order, cancellation timestamp and regeneration-idempotency changes |
| Frontend suite | 61 passed across 12 files |
| Java production JAR | Built successfully with isolated temporary Gradle output |
| TypeScript / Vite production bundle | Built successfully |
| Backend/frontend Docker images | Built successfully; final backend deployment rechecked below |
| Deployed Playwright UI smoke | Both pages, home cards, project history, progress, task prompts, invalid-task retry blocking, reference previews, narrow viewport, no page runtime errors |

The skipped existing tests were `AnalyticsIntegrationTest.representativeVolumeBenchmark` and `FeedbackIntegrationTest.representativeScaleBenchmark`; these opt-in tests were not needed for the bounded bulk acceptance run.

Full regression command: `powershell -ExecutionPolicy Bypass -File scripts/test-skills-backend.ps1 -All`. Final focused command: `powershell -ExecutionPolicy Bypass -File scripts/test-bulk-backend.ps1`. Frontend checks: `npm test`, `npm run build`, `node e2e/bulk-smoke.mjs` from `frontend/`.

## Live acceptance

Deployed using `docker compose -f compose.yaml -f compose.gpu.yaml`. Migration V16 was applied after creating `.tools/backups/bulk-before-v16-20261004.dump` (675668 bytes). Prior deployed migrations were not edited.

The local-only fixture script `scripts/smoke-bulk.py --submit` created project `152183d8-a752-4914-bbac-944d3555f5e0` with explicitly selected free mock providers:

| Batch | Completed | Validation failures | Provider attempts | Assets |
| --- | ---: | ---: | ---: | ---: |
| GPT Image `158ff6f6-3e9b-478e-bb7f-7ddf1d721842` | 125 | 1 intentionally invalid task | 125 | 125 |
| Gemini Video mock `7ea17151-66ad-41d5-98b0-fd629ec4f35b` | 2 | 0 | 2 | 2 |

The backend was restarted while the image queue was still processing. Every valid task completed with exactly one recorded attempt; no duplicate assets or extra billable submissions were created. The default rate limit remained in force.

`scripts/smoke-bulk.py --verify` downloaded both results ZIPs, checked manifest/task mapping, verified every output SHA-256 and asserted zero estimated/actual provider cost. The test generated **127 outputs**, all using mocks/local FFmpeg. No paid Gemini or OpenAI request was sent.

Evidence retained locally under ignored `frontend/test-results/bulk/`:

- `state.json`: stable import keys and project/batch IDs for safe replay.
- `verification.json`: final counts, timestamps and cost summaries.
- `GPT_IMAGE.zip`, `GEMINI_VIDEO.zip`: exported results and manifests.
- `image-dashboard.png`, `video-dashboard.png`, `video-narrow.png`: browser screenshots.

Local health checks passed for the backend readiness endpoint, frontend `/health`, PostgreSQL, MinIO and the GPU-overlay media workers.

## Explicit limits

Paid API behavior and account permissions were not exercised. Gemini's REST mapping was tested with a local fake HTTP server, including references, polling, quota classification, unsafe URLs and ambiguous submission IDs. The existing GPT operation has no image-reference support; these tasks are rejected explicitly. Unknown prices stay unknown. Cancellation cannot guarantee provider-side cancellation after submission. Delete preserves immutable media/history as a tombstone. See [bulk-generation.md](bulk-generation.md) for configuration, limits and recovery semantics.
