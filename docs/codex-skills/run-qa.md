# Run QA

Select explicit assetIds or project/collection/date scope; generationBatchId and generation status can narrow it. Convert today in the user timezone to from/to UTC instants. maxAssets rejects overbroad selections instead of silently truncating. Matching completed reviews and in-flight jobs are reused. forceRerun explicitly requests another evaluation. The existing service owns policy identity and evidence. Inspect quality findings through review details; NEEDS_REVIEW waits for a human, and rejection never auto-regenerates. This adapter supports full-policy evaluation only.

Read [`skills/run-qa/SKILL.md`](../../skills/run-qa/SKILL.md), its input reference and five examples. Use the shared plan/start/monitor/resume protocol in the [overview](../codex-skills.md).
