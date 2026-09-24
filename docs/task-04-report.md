# TASK-04 — Advanced Visual QA delivery report

Verified on 2026-09-24 in the existing Java 21/Spring Boot 4.1.1 monorepo. The local stack is running at http://localhost:3000. No paid AI call was made.

## Architecture and domain

`Asset → QA orchestrator → technical evidence + Vision evidence → policy → automatic/final decision → human review` extends the existing image generation, prompt snapshots, storage and cost system. `QualityReviewService` manages review identity/context and human decisions; `QualityWorker` manages bounded durable execution; `QualityPolicyEngine` evaluates evidence independently of providers. `VisionQualityProvider` has deterministic mock and OpenAI Responses adapters.

New/refactored concepts: advanced QualityReview, QualityFinding, QualityDimensionResult, versioned QaPolicy, QA job, Vision QA attempt, immutable HumanReviewAction, ReviewActor boundary, RegenerationRequest. Execution status is independent from content decision. Generation states now include QA_RUNNING and NEEDS_REVIEW. Assets reference the effective review; no duplicate asset-status lifecycle exists.

## Database

Applied additive Flyway V5, preserving V1–V4 and existing rows. It extends reviews, assets, collections, generation costs and shared provider permits; creates normalized findings/dimensions/jobs/attempts/actions/regeneration tables. Queue, review filter/history, issue-code/severity, attempt-order, idempotency and QA-cost indexes support retrieval. Database triggers protect frozen context/policies, completed automated results/evidence and human audit; historical reviews cannot be changed. Publication writes and PUBLISHED transitions require effective approval. A dedicated upgrade integration test verifies legacy decisions, reasons and effective-review attribution.

## Technical and visual QA

Technical evidence covers file readability, PNG/JPEG support and MIME agreement, file size, safe decode dimensions, minimum/requested resolution, aspect ratio, transparency and duplicate SHA-256. Deterministic sampled luminance/edge heuristics flag near-solid content, black borders, extreme clipping and very low detail with deliberately uncertain confidence. They are review hints, not calibrated blur or exposure classifiers.

Vision context uses the exact canonical/negative/adapted TASK-03 snapshot and resolved variables, collection/pipeline, asset metadata and generation provider/model. The evidence schema validates all nine dimensions: technical integrity, prompt compliance, subject integrity, anatomy, composition, visual coherence, text-free, watermark-free and crop quality. Findings use stable categories/codes, four severity levels, separate confidence, source, visible evidence and requirement/observation metadata. No aggregate quality number is created. The versioned prompt respects intentional fantasy and limits anatomy assessment to applicable subjects.

Mock scenarios cover clean output, minor/major artifacts, malformed face, unwanted text, watermark, low compliance, uncertainty, rate limit, timeout, invalid response and provider outage. OpenAI uses pinned `gpt-4.1-mini-2025-04-14` by default, strict Responses JSON Schema, bounded image/response sizes and a whole-exchange deadline. Its request/response contract, malformed output, missing fields, 401, 429, timeout and unsupported media are tested against loopback HTTP.

## Policies, human decisions and regeneration

Five versioned configurable profiles ship: default, wallpaper-standard, wallpaper-premium, stock and social. Pipeline mapping and collection references select the profile. Confidence thresholds, rejection codes and per-dimension minima decide APPROVED/NEEDS_REVIEW/REJECTED. Critical high-confidence evidence can reject; uncertain major evidence escalates. Missing/failed execution never approves. Premium/stock profiles preserve automatic approval while requiring a human final decision. The policy snapshot and triggered rules remain explainable after configuration changes.

Human approve/reject use revision checks and require reasons for contradictions or changes to prior human decisions. OTHER requires explanatory text. Original automated decisions remain intact. Human findings reopen final review; actions include actor, timestamps, before/after decisions and immutable audit. Batch review commits independent items and reports conflicts individually.

Regeneration supports SAME_PROMPT (frozen snapshots), MODIFIED_VARIABLES (new PromptEngine render of the parent's explicit version) and MANUAL_OVERRIDE (new ad-hoc snapshot). Parent linkage, feedback, request identity and audit are recorded. Originals and published prompt content are preserved. Idempotent regeneration replay returns the original child.

## Resilience, costs and observability

QA reuses the existing shared rate limiter, retry decisions, exception classification and cost ledger. PostgreSQL leases and an execution gate prevent duplicate dispatch; retries persist backoff with jitter/Retry-After. Limits bound local workers, cross-instance provider concurrency, rolling request rate, calls/review and lifetime calls/generation. Circuit state is persisted. Fallback is configurable and tested with an alternate free adapter; real-to-mock fallback is prohibited. Ambiguous paid outcomes are not automatically retried by default. A stale lease fails safely to human review for reconciliation.

Every Vision attempt is recorded with VISUAL_QA cost attribution to review/asset/generation/attempt, provider/model, usage, currency and pricing version. Unknown failed/unpriced amounts stay unknown. Review and generation details expose production costs. Metrics cover decisions, duration, findings, overrides, Vision requests/failures/cost. Structured logs omit raw images, full prompts, keys and provider bodies.

## Frontend

The dark Review workspace provides a paginated/filterable grid; automated/final decisions; severity, confidence and source; individual dimensions; exact prompt/variable comparison; fit/100%/zoom/pan; historical review inspection; audit and costs; override reasons; human findings; rerun and three regeneration modes. A/R/G and arrow shortcuts avoid typing fields. Batch selection and per-item results support high-volume review with large-rejection confirmation. Dashboard QA metrics, a QA job table, and collection policy selectors are integrated into existing pages.

## Verification results

| Check | Result |
|---|---|
| Backend unit tests | **58 passed**, no skips/failures |
| Backend integration tests | **51 passed**, PostgreSQL/pgvector and S3 Testcontainers |
| Frontend component tests | **21 passed** |
| Backend production build | `bootJar` passed locally and in Docker |
| Frontend production build | TypeScript + Vite passed locally and in Docker |
| 100 queued QA jobs | Completed; bounded concurrency; queue readable; provider calls outside DB transactions |
| Flyway upgrade | V1–V5 successful in the existing Compose database |
| Compose services | PostgreSQL, MinIO, backend and frontend healthy |
| Health endpoints | Backend UP; frontend `ok`; readiness/liveness verified |
| QA API smoke | Approval/rejection, uncertainty, both overrides, rerun history, regeneration replay/lineage, invalid-output failure, partial batch conflict, publication gate, queue and metrics passed |
| Browser | Headless Chromium verified dashboard/grid/detail/zoom/prompts/Escape/responsive viewport; no runtime errors; screenshots visually inspected |

Backend reports are in `backend/build/reports/tests/`. The repeatable browser script is `frontend/e2e/qa-smoke.mjs`; screenshots are ignored artifacts under `frontend/test-results/`. Desktop browser-control initialization failed on this host, so Playwright provided the independent browser verification.

Commands:

```sh
docker compose up -d --build
cd backend
./gradlew test integrationTest bootJar
cd ../frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

From the repo root run `pwsh -File scripts/qa-smoke.ps1` to create free QA demonstration data. Existing foundation/provider smoke scripts remain compatible. Windows local Gradle verification used an ASCII temporary checkout/cache to avoid the known non-ASCII worker-path problem; reports were copied back into the repo's ignored build directory.

## Real Vision verification

Real Vision provider implementation completed, but live Vision QA was not executed because credentials were unavailable.

`OPENAI_API_KEY` was absent from the current process and no workspace `.env` existed. No unrelated credential store was inspected. Real adapter tests used an explicit test key against a literal loopback mock server, never a paid endpoint.

## Remaining limitations

* This remains a private local workspace without user authentication; `local-workspace` is not a verified human identity. `ReviewActor` is the integration boundary for future authentication.
* Vision findings and heuristic confidence have not been calibrated on a labeled production dataset. Mock evidence is a scenario fixture, not semantic inspection of its pixels.
* Only one real Vision adapter ships. Additional real-provider fallback requires another adapter and configured route; automatic fallback to fabricated mock approval is forbidden.
* Technical decoding supports bounded PNG/JPEG, not arbitrary media or video QA. No dedicated OCR, face/anatomy detector or segmentation model is added.
* Actual provider charges require external reconciliation; custom Vision models have unknown estimates, and the pinned model estimate conservatively ignores cached-input discounts.
* Stock profiles do not certify licensing/releases or marketplace compliance. Publication is a gated local record, not external delivery. Full provider-quality analytics is deferred; normalized evidence, prompt/provider attribution and audit support it later.
* Published assets are locked against review mutations. The legacy same-prompt asset regeneration endpoint can create a new descendant without modifying the published original.
