---
name: prepare-stock
description: Prepare approved Media Factory images for stock processing, metadata review and checksum-verified CSV/export packages using configured stock profiles.
metadata:
  version: "1"
---

# prepare-stock@1

## Purpose and when to use

Reuse approved originals through TASK-08 validation, processing, metadata and export. Use for the requests described above, within Media Factory.

## Inputs

Required: projectId, explicit asset/collection scope, profile. Optional: maxAssets, exportPackage and exportProfile. Resolve actual profile identifiers from /stock-profiles and /stock-export-profiles. Unsupported target/output choices require an existing configured profile, not guessed marketplace rules.

## Preconditions

Read [shared execution conventions](../_shared/workflow.md) and [actual API reference](../_shared/api-reference.md). Confirm backend health, selected project, profiles and existing execution state. Backend validation is authoritative.

## Workflow

Inspect effective QA approval, source availability and similarity policy. Stock sources must belong to a STOCK_STRICT collection; do not silently change collection policy. Plan selected assets and the frozen stock profile. Start prepare-stock to reuse originals without image generation. Monitor normal stock processing, pixel-count/format validation and metadata generation. Surface title/description/keyword validation and warnings. Stop at METADATA_REVIEW for the actual human decision, then resume the same skill execution. Export only eligible approved productions through TASK-08. Download backend package bytes and verify manifest, CSV membership and checksums; do not manipulate production storage or invent stock-ready status. Preserve successful outputs if other items fail.

## Backend operations

Use /api/v1/stock-profiles, /stock-export-profiles, /stock-productions/{id}, /stock-productions/{id}/metadata, /stock-exports/{id}/validation and /stock-exports/{id}/content under /api/v1; orchestrate with skillName prepare-stock.

## Human approval points

Stock preparation does not authorize metadata approval or marketplace upload. Ask for the specific metadata/warning decision only once the reviewable result exists. Export preparation is local; external marketplace submission is outside this skill.

## Failure handling

Read [failure and resume handling](../_shared/error-handling.md). Reuse operationId and existing resource IDs. Preserve partial successes and immutable originals. Report a pending or failed state truthfully.

## Outputs

Selected/eligible/processed/ready/metadata-ready/exported/failed counts, production and package IDs, warnings, manifest/CSV/checksum verification, costs and remaining human decisions. Return a concise human summary plus the execution JSON/resource references.

## Examples

See [five operational requests](examples/requests.md) and [request schema](references/input.md).
