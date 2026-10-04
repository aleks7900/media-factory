# Actual Media Factory API

Base URL: `MEDIA_FACTORY_API` or `http://localhost:8080`. Request JSON is camelCase. Most JDBC response fields are snake_case. UUIDs are actual backend IDs. No skill needs database credentials.

## Workflow API (TASK-12)

| Operation | Request | Result / failures |
|---|---|---|
| Catalog | GET `/api/v1/skills` | Five names and version 1 |
| Plan/dry-run | POST `/api/v1/skills/executions` `{skillName,operationId,projectId,collectionId?,input}` | Frozen PLANNED record; no domain work. Identical operation ID replays; changed input conflicts. |
| Inspect | GET `/api/v1/skills/executions/{id}` | Plan, state, item/resource IDs, events and cost ledger aggregates |
| List | GET `/api/v1/skills/executions?projectId=UUID&page=0` | 50 records per page |
| Start/resume | POST `/api/v1/skills/executions/{id}/start` or `/resume` `{reason}` | RUNNING or waiting; asynchronous |
| Approve plan | POST `/api/v1/skills/executions/{id}/approve` `{reason}` | Attributed approval; not QA/publication approval |
| Cancel admission | POST `/api/v1/skills/executions/{id}/cancel` `{reason}` | Existing jobs/history retained |
| Research candidates | GET `/api/v1/skills/research/{researchRunId}` | Immutable sourced directions |

Unknown fields are rejected rather than silently ignored. Validation errors are 400, absent records 404 and lifecycle/idempotency conflicts 409 where supported. Inspect the actual response instead of assuming every domain exception uses the same status.

## Existing authoritative systems

| Area | Implemented reads | Mutation / important contract |
|---|---|---|
| Foundation | `/api/projects`, `/api/collections`, `/api/concepts`, `/api/generations`, `/api/assets`, `/api/jobs`, each with `/{id}` | Legacy collection/concept POSTs lack idempotency; skills use execution orchestration. |
| Providers | `/api/providers` | Enabled/default/model/capabilities; no exposed credentials. Pricing estimates are produced by the skill plan using PricingService. |
| Prompt Engine | `/api/v1/prompt-templates`, `/prompt-versions`, `/prompt-presets`, `/prompt-experiments` | Published versions are immutable; presets use keys. Generation preserves existing experiment assignment. |
| QA | `/api/v1/qa/policies`, `/qa/jobs`, `/reviews/{id}`, `/reviews?collectionId=...` | POST `/api/v1/assets/{id}/qa`; POST `/reviews/{id}/rerun` `{revision}`. Human approve/reject uses `{revision,reasonCode,reasonText}`. |
| Diversity | GET `/api/v1/collections/{id}/diversity` | POST `/api/v1/diversity/preflight` `{conceptId,prompt,semantic:false}`. Semantic mode may invoke an embedding provider; do not infer it is a free read. |
| Similarity | `/api/v1/assets/{id}/similar`, `/similarity-comparisons`, `/duplicate-groups`, `/embedding-jobs` | Never mark distinct or change canonical choices automatically to bypass a block. |
| Processing | `/api/v1/processing-profiles`, `/processing-runs/{id}`, `/assets/{id}/variants` | POST `/assets/{id}/process` `{profiles,manualCrops:{}}` with Idempotency-Key. Profiles own crop/resize dimensions. |
| Stock | `/api/v1/stock-profiles`, `/stock-export-profiles`, `/stock-productions/{id}` | POST `/stock-productions` `{sourceAssetId,profile}` + Idempotency-Key. Metadata approval: POST `/{id}/approve` `{revision,acknowledgeWarnings}` only with human authorization. |
| Stock export | `/api/v1/stock-exports/{id}`, `/{id}/validation`, `/{id}/content` | POST `/stock-exports` `{profile,stockProductionIds,incremental:false,policy:"STRICT"}` + Idempotency-Key. Backend creates ZIP/CSV/manifest and validates eligibility. |
| Wallpaper | `/api/v1/wallpaper-profiles`, `/wallpaper-device-profiles`, `/wallpaper-productions/{id}`, `/wallpaper-collections/{id}/production-status` | POST `/wallpaper-productions` `{conceptId,profile,metadata:{title,slug}}` + Idempotency-Key. Approved-original reuse is exposed through the skill adapter. |
| Wallpaper publication | `/api/v1/wallpapers/{id}/eligibility` | POST `/{id}/prepare-publication`; `/{id}/approve-publication` `{packageId,revision}`; `/{id}/publication-dry-run` or `/publish` `{target}`. Approval is explicit. Real Android adapter remains unavailable. |
| Wallpaper export | `/api/v1/wallpaper-exports`, `/{id}/content` | POST `/api/v1/wallpapers/{id}/export`; frozen package, no external publication implied. |
| Video | `/api/v1/video-productions`, video profiles/dashboard | Inspect actual VideoController for any requested future video skill; these five skills do not generate video. |
| Analytics | `/api/v1/analytics/overview`, `/assets`, `/collections`, `/costs`, `/experiments`, `/data-quality` | TASK-10 measurements remain authoritative; never create synthetic performance as trend evidence. |
| Feedback | `/api/v1/feedback/attributes`, `/findings`, `/learnings`, `/saturation`, `/experiments` | Findings are observational evidence. Trend-derived ideas are not manufactured findings or automatic trial approval. |

Paths in shortened entries retain their area's stated prefix. This reference was checked against the Java controllers; consult controller DTOs when performing an operation not covered here. Lists have different bounds and pagination conventions.
