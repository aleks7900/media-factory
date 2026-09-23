# Prompt assets

Production templates, versions, variable definitions and preset revisions are managed by the database-backed Prompt Engine through `/api/v1/prompt-*` APIs and the Prompt Library UI. Files here are reference/import examples, not a second source of mutable production prompt content.

`image-concept-v1.md` remains a TASK-01 reference. New generation requests use a published prompt version with variables/presets, or an explicit ad-hoc snapshot for raw-string compatibility. See [prompt engine](../docs/prompt-engine.md), [versioning](../docs/prompt-versioning.md), and `scripts/prompt-smoke.ps1` for runnable examples.
