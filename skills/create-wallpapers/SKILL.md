---
name: create-wallpapers
description: Generate Media Factory Android wallpapers or reuse approved wallpaper-collection originals to create profile-controlled masters, device variants, previews and publication packages.
metadata:
  version: "1"
---

# create-wallpapers@1

## Purpose and when to use

Orchestrate TASK-07 through existing generation, QA, similarity, processing, AMOLED and publication-preparation gates. Use for the requests described above, within Media Factory.

## Inputs

Required: projectId, wallpaper collectionId and profile. Generation: conceptIds (or collection concepts), targetCount, optional provider/model and maxBudget/currency. Reuse: processOnly true with explicit assetIds from that wallpaper collection. Optional: amoled, metadata, exportPackage and publishToBackend. Profiles control dimensions/devices/previews.

## Preconditions

Read [shared execution conventions](../_shared/workflow.md) and [actual API reference](../_shared/api-reference.md). Confirm backend health, selected project, profiles and existing execution state. Backend validation is authoritative.

## Workflow

Read collection and authoritative wallpaper/device profiles; match AMOLED and similarity policy. Review scoped Feedback and saturation evidence. Dry-run with planned generation count, unavailable/known costs and expected processing profiles. Process-only must reuse approved originals with the required QA policy, never regenerate them. Generation is staged through existing jobs; inspect QA and similarity before subsequent stages. Respect PAUSED_DIVERSITY and rejected/failed states. Monitor master/device variant/preview lineage and AMOLED classification. Prepare the publication package only after backend eligibility succeeds. Never equate processing with publication. The Android backend remains a future adapter: use existing mock/dry-run/export capabilities and report the limitation. Wallpaper experiment enrollment is currently unsupported; stop rather than silently dropping experiment attribution.

## Backend operations

Use /api/v1/wallpaper-profiles, /wallpaper-device-profiles, /wallpaper-productions/{id}, /wallpapers/{id}/eligibility, /wallpapers/{id}/prepare-publication and wallpaper exports through skillName create-wallpapers. Confirm actual routes in the shared reference before invoking.

## Human approval points

QA review and publication approval remain separate backend gates. publishToBackend true requests a publication workflow; it never grants the skill authority to fabricate human approval. Surface a concrete package for approval and retain WAITING_FOR_APPROVAL until the real gate is satisfied.

## Failure handling

Read [failure and resume handling](../_shared/error-handling.md). Reuse operationId and existing resource IDs. Preserve partial successes and immutable originals. Report a pending or failed state truthfully.

## Outputs

Requested/attempted/generated/QA outcomes/duplicates/processed/master/device variant/preview/backend-ready/published counts where authoritative, production/package IDs, costs, failure reasons and approval needs. Return a concise human summary plus the execution JSON/resource references.

## Examples

See [five operational requests](examples/requests.md) and [request schema](references/input.md).
