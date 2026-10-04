# Prepare stock

Select approved, similarity-eligible source assets from STOCK_STRICT collections and an existing stock profile. The adapter rechecks eligibility before dispatch. No replacement images are generated. Existing stock services perform processing, technical validation and metadata generation. Metadata approval remains human. exportPackage with exportProfile requests a STRICT export only after eligible productions reach READY_FOR_EXPORT. The existing export download verifies its checksum. Reprocessing and metadata regeneration require their explicit domain operations; resume preserves resource IDs.

Read [`skills/prepare-stock/SKILL.md`](../../skills/prepare-stock/SKILL.md), its input reference and five examples. Use the shared plan/start/monitor/resume protocol in the [overview](../codex-skills.md).
