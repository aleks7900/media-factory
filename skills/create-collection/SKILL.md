---
name: create-collection
description: Plan and create Media Factory collections and diversified concepts from a creative direction or recorded trend research; optionally queue explicitly requested image generation.
metadata:
  version: "1"
---

# create-collection@1

## Purpose and when to use

Persist a creative plan, collection and varied concepts while preserving prompt and research lineage. Use for the requests described above, within Media Factory.

## Inputs

Required: projectId, name, concepts [{name,prompt,variables?}]. Optional: mediaType IMAGE/WALLPAPER/STOCK, description, slug, theme and style (required for WALLPAPER), amoled, targetAssetCount, diversityPlan, trendCandidateId, promptVersionId, presetKeys, experimentId, generate, provider/model, dimensions, maxBudget/currency.

## Preconditions

Read [shared execution conventions](../_shared/workflow.md) and [actual API reference](../_shared/api-reference.md). Confirm backend health, selected project, profiles and existing execution state. Backend validation is authoritative.

## Workflow

Resolve the project and inspect existing collections. Review relevant TASK-11 findings/learnings and saturation as evidence, not mandatory creative instructions. Plan variation across subject, framing, composition, palette and environment; percentages describe intent, not guaranteed output. Choose published prompt/profile identities from the backend. Default to create-only unless generation was explicitly requested. Dry-run first; show concept count, target count, prompt strategy, cost availability and diversity warnings. Start the execution to create the collection and concepts atomically. For specialized collections, compose create-wallpapers or prepare-stock afterward. Ordinary image generation uses the existing prompt engine and staged jobs. Preserve any experimentId; reject unsupported experiment/pipeline combinations rather than dropping attribution.

## Backend operations

Read /api/projects, /api/collections, /api/v1/prompt-versions, /api/v1/prompt-presets, /api/providers and relevant Feedback/Analytics. Use skillName create-collection for idempotent creation; do not manually POST the old non-idempotent collection endpoint on a retry.

## Human approval points

Creating the requested collection is authorized by the request. Creating it does not authorize generation. Present the concrete plan before requesting any missing paid/batch approval. Published prompts and production policies are not edited by this skill.

## Failure handling

Read [failure and resume handling](../_shared/error-handling.md). Reuse operationId and existing resource IDs. Preserve partial successes and immutable originals. Report a pending or failed state truthfully.

## Outputs

Execution/collection/concept IDs, media type, target assets, variation plan, prompt version/presets, research lineage, estimates and actual generation status. Return a concise human summary plus the execution JSON/resource references.

## Examples

See [five operational requests](examples/requests.md) and [request schema](references/input.md).
