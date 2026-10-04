# TASK-12 delivery report

## Skills and workflow boundary

All five version-1 skills are installed, discoverable and selected correctly in a real read-only Codex acceptance session. Canonical folders contain SKILL.md, agents/openai.yaml, input references and five natural-language examples each. Shared conventions and the Node HTTP client are under skills/_shared. The installer copies owned resources to .agents/skills and supports this Windows workspace's Cyrillic path using explicit UTF-8 markers. Root AGENTS.md describes invocation, budgets, lineage and human gates.

| Skill | Inputs and trigger | Workflow and outputs | Approval boundary |
|---|---|---|---|
| research-trends | Project, topic, public source observations | Cited directions and immutable research/candidate IDs; external evidence separate from internal results | Research does not authorize generation |
| create-collection | Project, creative direction, diverse concepts; wallpaper theme/style/slug | Dry-run, collection/concepts, trend lineage; optional explicit staged IMAGE generation | Paid batch approval and authoritative budget required |
| run-qa | Project plus assets, collection, batch or dates | Existing policy evaluation, matching-review reuse, decisions and finding codes | Human decisions stay separate; forceRerun explicit |
| prepare-stock | Eligible approved assets, stock/export profiles | Existing processing, technical QA, metadata, human review and CSV/manifest export | Metadata approval remains a gate |
| create-wallpapers | Wallpaper collection/profile, concepts or approved originals | Existing master/device variants, preview, thumbnail, AMOLED analysis, package/export | Processing never authorizes publication |

## Backend integration

V15 adds durable skill executions, item/resource links, immutable events, research provenance and image-budget reservations. Operation IDs replay identical requests and reject changed input. Frozen plans contain scope, versions, estimates and warnings; planning starts no domain jobs. Stages invoke existing services rather than duplicating business rules. Resume retains successful resources and revisits failed jobs after explicit domain recovery; it does not create replacement images.

TASK-02 owns provider routing/attempts; TASK-03 owns prompt rendering, presets and normal IMAGE experiment assignment. TASK-04 owns QA policy, findings and human review. TASK-05 owns duplicate/diversity checks. TASK-06 owns processing and immutable variants. TASK-07 and TASK-08 own wallpaper/stock lifecycle and exports. TASK-10's cost ledger remains authoritative. TASK-11 feedback and saturation evidence is exposed during planning, with diversity warnings recorded in execution events. Specialized wallpaper experiment enrollment is rejected explicitly rather than losing attribution.

## Safety and operating limits

Image admission checks provider/model, authoritative price, spent cost, outstanding reservations and maxBudget immediately before attempts. Interrupted reservations require reconciliation. Unknown paid image pricing blocks execution. Paid QA and stock text/vision are blocked at start and dispatch until authoritative preflight bounds exist. Mock routes support the complete demonstrated pipeline. Compute cost is unpriced, not zero by inference.

Human QA, stock metadata and publication remain distinct approvals. Cancellation prevents new skill admission; it cannot undo in-flight external requests or remove retained outputs. External content is untrusted data; source URLs cannot carry credentials, and the backend does not fetch arbitrary research URLs. Tokens are environment-only; client errors avoid raw provider payloads. Local export writes verify SHA-256 and refuse overwrite/path traversal.

The backend has no project ACL or user authentication. Scope validation is not an authorization boundary; remote use requires an authenticated gateway. Execution cost totals are linked ledger costs since planning, with actual-unavailable counts; concurrent shared-asset work can overlap attribution. They are not exclusive invoices. The skills expose this limitation explicitly.

Real Android wallpaper backend integration was not implemented because no external backend repository/API contract was provided. The publication boundary, mock implementation, dry-run flow and future backend contract were implemented instead.

## Verification

See [testing evidence](testing.md) for exact results and IDs. Full suite: 125 backend unit tests passed, 129 integration tests passed and two skipped. Nine focused integration tests passed following the final changes. Frontend: 56 passed. Client: five passed. All five skill files passed official validation. Java/frontend production builds and Docker images built successfully.

Live mock research/collection/wallpaper export, stock export and QA reuse completed. Stock: valid 4.19 MP JPEG, CSV, manifest and checksum-verified archive. Wallpaper: six variants and a checksum-verified archive, retained at PUBLICATION_REVIEW. Duplicate rejection was preserved. No paid media generation or real publication occurred. Research evidence in these scenarios was clearly labelled synthetic; no market-trend claim is made.

## Run

Use [the setup guide](../codex-skills.md) for installation and plan/start/monitor/resume commands. Start infrastructure with `docker compose -f compose.yaml -f compose.gpu.yaml up -d --build --wait`. Local dashboard: http://localhost:3000; API health: http://localhost:8080/actuator/health. Retain the GPU overlay on this workstation.

Final deployment on 2026-10-04: all seven long-running Compose services healthy; backend health UP, frontend health ok, five skills exposed. Final HTTP evidence: backend/build/task12-final-health.json.
