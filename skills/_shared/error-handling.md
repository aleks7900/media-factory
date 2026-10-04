# Resume and failures

GET the skill execution after an uncertain network response. Re-submit the identical plan with the same operationId only when its response was lost. Start/resume transitions are idempotent; completed resources remain attached to execution items.

| State or signal | Action |
|---|---|
| PLANNED | Inspect plan; start only within user authorization. |
| RUNNING | Poll with bounded backoff; do not create replacement work. |
| WAITING_FOR_APPROVAL | Inspect item codes and domain review. Ask for the particular missing decision, not blanket approval. |
| PARTIALLY_COMPLETED | Report successful IDs and failures; resume retains existing resources. |
| FAILED | Inspect domain failure and retry policy. Do not auto-regenerate. |
| CANCELLED | Report retained resources; do not restart automatically. |
| SIMILARITY_BLOCKED / PAUSED_DIVERSITY | Show evidence and propose diversification. Never mark duplicates distinct merely to advance. |
| QA_REJECTED | Preserve rejection; regeneration is a separate authorized operation. |
| BUDGET_EXCEEDED / unknown price | Stop admission; do not guess costs. |
| HTTP 401/403 | Authentication/authorization failure; no alternate credential or endpoint guessing. |
| HTTP 409 | Read current revisions and original idempotency input. |
| Timeout / 429 / 5xx | Read state first. Backend owns provider retry/reconciliation. |

Never report success solely because POST returned 200 or a queue ID exists. Reports cite authoritative resource IDs and status, with backend error codes where available. Do not include raw provider diagnostics, tokens, signed URLs or request headers.
