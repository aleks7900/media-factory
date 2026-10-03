# Learning library

A learning references its immutable experiment result, hypothesis and explicit scope. Its summary describes an observed comparison and evidence state, not a production instruction. Navigate Learning → Experiment → Hypothesis → Finding → Analysis → evidence assets; the reverse relationships remain in persisted IDs and result histories.

States: ACTIVE, SUPERSEDED, CONTRADICTED, STALE. POST `/feedback/learnings/{id}/relate` with status, relatedLearningId (required for supersession/contradiction), reason and user. Both records must have the same scope. Contradiction marks both as CONFLICTING_EVIDENCE and retains both numerical results. Supersession preserves the old record and points to the replacement. No LLM chooses which evidence to erase or believe. Summaries, scope and result references cannot be edited after creation.

Learnings become stale after their stored deadline (90 days initially). Staleness does not delete evidence. Collection/platform findings stay in scope; a wolf-wallpaper trial is not global stock-image knowledge. Adopting a learning later must be an explicit versioned prompt/configuration change through the owning production subsystem.

The library shows status, evidence, linked experiment/hypothesis, and paged filtering. Review identity follows the existing trusted local-workspace model; it is not a new multi-tenant authentication system.
