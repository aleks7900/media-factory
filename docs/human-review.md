# Human review

Open Review for the paginated effective-review grid. Filter by decision, execution, severity, issue code, provider, pipeline, collection and creation date. Select visible assets, automatic approval candidates or human-review candidates. Batch approve/reject returns per-item success/error; a stale item does not roll back other valid items. Rejection of ten or more selected assets requires explicit UI confirmation.

Inspect evidence opens the full-resolution workspace: fit, 100%, zoom and pointer-drag pan; dimensions and confidence; severity-grouped findings with source; exact canonical/adapted prompts and variables; policy explanation; attempts/costs; QA history and human audit. A/R/G and left/right support keyboard review. Shortcuts ignore input/select/textarea/contenteditable fields; rejection asks for confirmation and overrides never bypass reason validation. Escape closes the workspace, Tab stays inside its dialog.

Approve/reject submit the displayed revision. Server-side row locks and optimistic comparison reject a stale or superseded review with HTTP 409. QA must finish before human decisions. A failed execution may receive a human decision, but its execution status remains FAILED and its automatic verdict remains null. Published assets cannot be changed.

Contradicting an automatic APPROVED/REJECTED verdict requires a reason. Changing a previous human decision also requires a reason. Codes: AI_FALSE_POSITIVE, AI_FALSE_NEGATIVE, INTENTIONAL_STYLE, PROMPT_CONTEXT, TECHNICAL_EXCEPTION, MANUAL_QUALITY_JUDGMENT, OTHER. OTHER requires text. Adding a human finding records evidence without rewriting AI findings and returns final decision to NEEDS_REVIEW. Human approval after a finding is an explicit separate action.

`human_review_actions` is an immutable audit of APPROVE, REJECT, OVERRIDE_APPROVAL, OVERRIDE_REJECTION, ADD_FINDING, REQUEST_REGENERATION, with previous/new decisions, actor, reason and time. Original automated decision and findings are preserved. `ReviewActor` is the authentication integration boundary; the current private deployment records `local-workspace`, not a verified personal identity. An authenticated gateway and a principal-based implementation are required for multi-user production use.

## API

| Method/path | Request |
|---|---|
| GET `/api/v1/reviews/queue` | `page`, `size` (1–100), `decision`, `executionStatus`, `collection`, `pipeline`, `finding`, `severity`, `provider`, `createdFrom`, `createdTo` |
| GET `/api/v1/reviews/{id}` | Full evidence, frozen context/policy, attempts, history, costs, audit |
| POST `/api/v1/reviews/{id}/approve` or `/reject` | `{revision, reasonCode, reasonText}` |
| POST `/api/v1/reviews/{id}/findings` | `{revision, finding:{category,code,severity,confidence,detected,source:"HUMAN",evidence,metadata}}` |
| POST `/api/v1/reviews/{id}/rerun` | `{revision, mockScenario?}` |
| POST `/api/v1/reviews/batch` | `{decision:"APPROVED" or "REJECTED",items:[{id,revision,reasonCode,reasonText}]}` |
| GET `/api/v1/qa/jobs` | Latest 200 QA jobs |
| GET `/api/v1/qa/dashboard` | Effective-review metrics |
| GET `/api/v1/qa/policies` | Available versioned profiles |

Legacy `/api/reviews` remains readable. Legacy decision writes route through the same validation but cannot specify an override code; clients needing overrides must use v1. Old review reasons are retained, and rerun can create an advanced review for a legacy asset. Audit/findings/dimensions can be joined to generation prompt versions, variants and providers for later feedback analytics; a full analytics dashboard is not part of this change.
