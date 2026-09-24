# Regeneration

Regeneration always creates a new Generation with `parent_id`, a new image job, new prompt snapshots and eventually a new asset/storage key. The original image, original prompt version, automated findings and human decision remain intact. `regeneration_requests` records review, parent/child IDs, mode, request, feedback, actor and timestamp.

`POST /api/v1/reviews/{id}/regenerate` requires `Idempotency-Key` and `{revision,mode,variables?,prompt?,negativePrompt?,feedback?}`. Replaying the same key and payload returns the same child; a different payload conflicts. Human audit records REQUEST_REGENERATION and increments the review revision.

* SAME_PROMPT copies the exact frozen parent snapshots, variables, preset revisions, route and attribution, including when the catalog has subsequently changed.
* MODIFIED_VARIABLES keeps the parent's explicit immutable template version, combines its resolved variables with the submitted changes, and invokes PromptEngine to create new snapshots. The version must still be eligible for a new production render; ad-hoc/legacy generations require manual mode. Preset keys are resolved to current preset revisions for this new composition and snapshotted; the old generation is unchanged.
* MANUAL_OVERRIDE validates explicit positive/negative text and creates a new ad-hoc PromptEngine snapshot, retaining parent lineage and the parent provider route.

Feedback is stored as review context, not silently appended to prompts or used to rewrite a published template. A reviewer wanting a prompt change chooses modified variables or manual override explicitly. Legacy `/api/assets/{id}/regenerate` remains same-prompt behavior, but review-aware clients should use v1 to retain the human feedback/audit record.
