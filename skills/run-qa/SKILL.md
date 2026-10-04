---
name: run-qa
description: Run or rerun Media Factory quality evaluation for selected generated assets, collections or date ranges; reuse matching reviews and report actionable QA results.
metadata:
  version: "1"
---

# run-qa@1

## Purpose and when to use

Invoke TASK-04 quality evaluation and report its authoritative findings without making review decisions. Use for the requests described above, within Media Factory.

## Inputs

Required: projectId plus explicit assetIds or collection/date scope. Optional: collectionId, generationBatchId, status, from/to UTC instants, profile, forceRerun and maxAssets. Resolve today using the user timezone into an explicit half-open UTC interval.

## Preconditions

Read [shared execution conventions](../_shared/workflow.md) and [actual API reference](../_shared/api-reference.md). Confirm backend health, selected project, profiles and existing execution state. Backend validation is authoritative.

## Workflow

Resolve the exact selection and inspect current reviews/policy versions. Plan the frozen asset scope. Start run-qa; the backend reuses completed matching reviews unless forceRerun is requested or the policy version changed. It also reuses in-flight QA. Monitor review execution separately from final decisions. Group detected findings by actual backend codes, linking assets/reviews to the Review workspace. NEEDS_REVIEW remains a human gate. A rejected asset is a completed quality outcome, not a reason to auto-regenerate. Published originals cannot be re-reviewed by this API; report the backend restriction. technicalOnly or disabling visual QA is unsupported by the current full-policy adapter and must not silently change a policy.

## Backend operations

Use /api/v1/qa/policies, /api/v1/reviews, /api/v1/reviews/{id}, /api/v1/qa/jobs, and skillName run-qa. Do not implement image-quality checks in this skill or call approval endpoints merely to finish.

## Human approval points

A request to run QA authorizes evaluation, not approval/rejection overrides. Vision charges remain subject to backend configuration; disclose unavailable estimates. Request missing authorization before paid evaluation when required by the existing project policy.

## Failure handling

Read [failure and resume handling](../_shared/error-handling.md). Reuse operationId and existing resource IDs. Preserve partial successes and immutable originals. Report a pending or failed state truthfully.

## Outputs

Selected/checked/reused/running/approved/needs-review/rejected/failed counts where available, major actual finding codes, review IDs, recorded QA costs and unresolved gates. Return a concise human summary plus the execution JSON/resource references.

## Examples

See [five operational requests](examples/requests.md) and [request schema](references/input.md).
