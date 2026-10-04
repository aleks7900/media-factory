# Create collection

Plan a project-scoped collection with unique concept names and varied prompts. mediaType selects IMAGE, STOCK or WALLPAPER; wallpaper requires a safe slug. Creation defaults to no generation. Explicit IMAGE generation supports provider/model, budget, published promptVersionId, presetKeys and concept variables. Specialized generation is composed through the wallpaper or stock workflow. Each image job carries skill_execution_id. Stages reevaluate diversity and stop for high repetition risk. Existing feedback and saturation evidence is returned for an existing collection; a new collection has no invented performance history.

Read [`skills/create-collection/SKILL.md`](../../skills/create-collection/SKILL.md), its input reference and five examples. Use the shared plan/start/monitor/resume protocol in the [overview](../codex-skills.md).
