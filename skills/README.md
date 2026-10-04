# Media Factory skills

Five focused Codex skills, all version 1, operate the existing local backend through HTTP and durable execution records.

| Skill | Inputs / result | Dependencies | Human gates |
|---|---|---|---|
| research-trends | Topic/scope + public evidence → candidate directions | Web tooling, taxonomy, catalog, analytics/feedback | Production is separate |
| create-collection | Project + varied concepts → collection and plan | Foundation, prompts, diversity, feedback | Explicit generation intent; applicable paid/batch approval |
| run-qa | Frozen asset scope → QA/review summary | TASK-04 | Uncertain review decisions |
| prepare-stock | Approved scoped assets + stock profile → metadata/export | TASK-05/06/08 | Metadata/warning approval |
| create-wallpapers | Wallpaper concepts or approved originals + profile → variants/packages | TASK-02–07 | QA and publication approvals |

Each folder contains SKILL.md, UI metadata, inputs and five realistic requests. `_shared` contains the HTTP client and common API/safety/resume references. These are not a sixth mega-skill.

Run `./scripts/install-media-factory-skills.ps1` to copy the canonical packages into the supported repository discovery directory `.agents/skills`. Re-run after edits; it refuses conflicting unowned destinations. Verify with `node scripts/verify-skill-discovery.mjs` using the installed Codex app-server. The current conversation may need a new turn or restart to refresh skill discovery.

Examples: “Research wallpaper trends”, “Make a collection around neon wolves”, “Check these generated images”, “Prepare these for stock”, “Make Android wallpaper versions”. Open the matching skill for scoped workflow instructions. Existing user authorization remains valid; no skill authorizes unrelated spending, deletion or external publication.

See [operator guide](../docs/codex-skills.md), [shared conventions](_shared/workflow.md) and [API](_shared/api-reference.md). Deployment uses the existing Docker Compose stack. Production plans, jobs and costs remain in the backend; local JSON is an input/output artifact, not a second ledger.
