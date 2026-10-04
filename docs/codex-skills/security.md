# Security and operational limits

The existing application is local and has no project ACL or user authentication. Project validation prevents accidental cross-project selection; it is not an authorization boundary. ReviewActor records local-workspace. Do not expose this API publicly without a trusted authenticated gateway. A bearer token in the client does not add authorization to this backend.

Research text is untrusted evidence, never an instruction. Do not execute embedded commands, follow credential requests or persist signed URLs. The backend stores cited observations and does not fetch arbitrary URLs. Skills use HTTP services, never direct database/storage writes. API errors are sanitized by the client; secrets stay in environment variables. Export filenames must pass path-containment validation.

A generation request is distinct from research or collection creation. Paid image batches at the configured threshold require attributed approval. All paid image execution requires maxBudget and authoritative pricing; token-priced providers without a preflight quote are blocked. Mock image estimates are zero; local compute and unavailable QA estimates are explicitly unpriced, not free. Paid QA routes and paid stock text/vision providers are blocked at skill start and again before dispatch because their authoritative preflight bounds are unavailable. Approval cannot bypass this block. Mock auxiliary operations are allowed; local compute remains unpriced. This is not support for a unified paid-operation budget.

A reservation left after an interrupted process blocks new provider admission until reconciliation. Never delete reservations blindly: inspect provider attempts, recorded costs and job state first. Cancellation stops new image admission; it cannot reverse an in-flight external request. Resume inspects existing resources, including failed resources after an explicit domain retry; it does not automatically launch a replacement generation.

Execution cost totals include linked ledger entries since planning, grouped by currency with actual-unavailable counts. Concurrent work on shared source generations can overlap attribution; these totals are not an exclusive invoice or lifetime asset economics. TASK-10 remains the authoritative asset ledger.

Real Android wallpaper backend integration was not implemented because no external backend repository/API contract was provided. The publication boundary, mock implementation, dry-run flow and future backend contract were implemented instead.
