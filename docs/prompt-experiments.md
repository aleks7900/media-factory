# Prompt experiments

Experiments compare published versions of one logical template. There are 2–20 variants, with unique keys and positive integer basis-point weights totaling exactly 10,000. `5000/5000` means 50/50; `8000/2000` means 80/20; `4000/3000/3000` supports three directions without changing the model.

Supported scope targets are PROMPT_TEMPLATE (`promptTemplateId`), COLLECTION (`collectionId`, resolved from the request's concept), and PIPELINE (`pipelineKey`: default/wallpaper/stock). Exactly one corresponding target is required. Template-scoped variants must belong to that template; all experiments' variants must share one template. Generation requires a base `promptVersionId` identifying the direction. The chosen experiment variant supplies the final version.

The engine finds running experiments eligible for that request. No match uses the explicit version. Multiple matches are an error, resolved by explicitly selecting an eligible `experimentId`; no hidden scope priority exists. A paused/completed experiment does not automatically enroll new requests. Explicitly requesting a non-running/out-of-scope experiment fails rather than silently removing attribution. Preview needs equivalent context and an assignment key to display an experiment result.

## Assignment

Algorithm `SHA256_BASIS_POINTS_V1` hashes UTF-8 `experimentUUID + ':' + assignmentKey`. It interprets the first eight bytes as an unsigned big-endian integer, takes remainder 10,000, and walks variants in key order through cumulative integer weights. No floating-point random allocation is used.

For new generation requests the stable assignment key is the generation idempotency key. The engine records this key and final variant in the immutable snapshot and generation foreign keys. A replay checks the original request first and returns the existing assignment even after the experiment pauses or ends. Preview accepts an explicit `assignmentKey` and does not enroll a generation. Regeneration intentionally repeats its parent's frozen attribution instead of re-enrolling with its new job key; `parent_id` allows analytics to distinguish repeats.

## Lifecycle and integrity

```text
DRAFT → RUNNING ↔ PAUSED
RUNNING → COMPLETED
DRAFT / RUNNING / PAUSED → CANCELLED
```

Completed/cancelled experiments cannot restart; pause resumes through `/start`. All lifecycle commands require optimistic `revision`. Start checks published variant versions and active template status. Configuration has no update API; create a replacement experiment to change versions, weights, scope, assignment logic or override policy. Database triggers independently freeze experiment configuration and variants once the experiment leaves draft, including while paused.

Manual suffixes are prohibited by default. Set `allowOverrides=true` explicitly when creating an experiment if they are part of its design. Snapshots expose every suffix so analytics can filter these generations.

## API

```json
{
  "name":"Cyber Wolf Lighting",
  "scope":"PROMPT_TEMPLATE",
  "promptTemplateId":"<template-uuid>",
  "allowOverrides":false,
  "variants":[
    {"key":"A","name":"Original","promptVersionId":"<v1-uuid>","weight":5000},
    {"key":"B","name":"Rim lighting","promptVersionId":"<v2-uuid>","weight":5000}
  ]
}
```

POST this to `/api/v1/prompt-experiments`. GET list/detail includes variants and current generation counts. Lifecycle endpoints are `/start`, `/pause`, `/complete`, `/cancel`, each with `{revision: n}`. The UI exposes creation, 2+ variant weights, scope, override policy, lifecycle and attributed counts.

## Analytics boundary

Foreign keys connect experiment → variant → generation → asset → publication → performance metric. Per-attempt costs already join through generation. These relationships support future approval/rejection/cost/download/view/CTR/revenue analysis without inventing results now. TASK-03 does not automatically determine a statistically valid experiment winner. Counts include regenerations and are descriptive only. Stratification, exposure accounting, confidence intervals, winner selection and performance ingestion belong to later analytics work.
