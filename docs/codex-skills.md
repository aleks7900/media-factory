# Codex operational skills

TASK-12 adds five versioned workflows over the existing Media Factory services. Canonical sources are in `skills/`; no skill implements a second generation, QA, similarity, processing or publication engine.

## Install and discover

From the repository root run `powershell -File scripts/install-media-factory-skills.ps1`, then `node scripts/verify-skill-discovery.mjs`. The installer only updates directories bearing its repository ownership marker. Repeat it after editing canonical sources. Codex discovers repository skills in `.agents/skills`, as documented in [OpenAI's skill documentation](https://learn.chatgpt.com/docs/build-skills). If an existing session does not refresh, start a new session.

The verification script uses the installed Codex app-server `skills/list` operation and writes sanitized evidence to `backend/build/task12-skill-discovery.json`. Discovery alone does not establish natural-language selection or end-to-end success.

## Execute

Set `MEDIA_FACTORY_API` (default `http://localhost:8080`). Optional `MEDIA_FACTORY_TOKEN` is read from the environment and never printed. Remote connections require HTTPS and a trusted authenticated gateway.

1. Read the selected skill and shared references.
2. Resolve project, collection, profile and exact input scope through existing APIs.
3. Write a request envelope containing `skillName`, stable `operationId`, `projectId`, optional `collectionId`, and `input`.
4. `node skills/_shared/client.mjs plan request.json` records a frozen dry-run plan only.
5. Inspect the returned plan and any missing approval/budget. `node skills/_shared/client.mjs start EXECUTION_UUID "User requested this workflow"` starts authorized work.
6. `node skills/_shared/client.mjs monitor EXECUTION_UUID` polls for a bounded interval. Pending or human-review work is not reported as completed.
7. After resolving the actual domain gate, use `resume` with the same execution UUID. Do not create a new operation ID to retry a lost response.

The API is `/api/v1/skills/executions`; detail includes items, domain IDs, audit events, recorded costs and results. No frontend page is required to invoke skills. Existing Review, Stock and Wallpaper workspaces remain the human decision surfaces.

## Architecture

`SkillPlanService` validates and freezes scope. `SkillExecutionService` owns operation-ID conflict detection, audit, lifecycle and polling. `SkillDomainOperations` dispatches stages through existing services. `SkillBudgetGuard` checks image provider admission immediately before each attempt. V15 stores executions, items, immutable events, research evidence and domain attribution. Dry-run writes only planning/audit data.

Configuration: `codex.skills.enabled=true`, `codex.skills.stage-size=5` (clamped 1–20), `codex.skills.approval.generation-batch-threshold=20`. Execution polling is bounded by the worker and client; human gates require explicit resume. Cancellation prevents new image admission but cannot undo an already executing provider call or erase existing assets.

## Guides

- [Research](codex-skills/research-trends.md)
- [Collections](codex-skills/create-collection.md)
- [QA](codex-skills/run-qa.md)
- [Stock](codex-skills/prepare-stock.md)
- [Wallpapers](codex-skills/create-wallpapers.md)
- [Security and limitations](codex-skills/security.md)
- [Testing evidence](codex-skills/testing.md)

See the [delivery report](codex-skills/report.md) for supported capabilities, acceptance results and remaining limits.
